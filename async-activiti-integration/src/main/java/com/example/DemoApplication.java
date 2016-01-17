package com.example;

import org.activiti.engine.ProcessEngine;
import org.activiti.engine.impl.bpmn.behavior.ReceiveTaskActivityBehavior;
import org.activiti.engine.impl.pvm.delegate.ActivityExecution;
import org.activiti.spring.integration.Activiti;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * This example demonstrates how to launch an Activit workflow process that then kicks of a Spring Integration flow that
 * returns in an out-of-band request (potentially minutes, hours, or years later). This doesn't block: messages
 * are forwarded to Spring Integration where they may do anything they want. Only a subsequent message
 * containing the {@code executionId} header signal's the execution of the process.
 *
 * @author Josh Long
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @RestController
    public static class ProcessRestController {

        @Autowired
        private ProcessEngine processEngine;

        @Autowired
        @Qualifier("resume")
        private MessageChannel resume;

        @RequestMapping(method = RequestMethod.GET, value = "/resume/{executionId}")
        public void resume(@PathVariable String executionId) {
            Message<String> build = MessageBuilder.withPayload(executionId)
                    .setHeader("executionId", executionId).build();
            this.resume.send(build);
        }

        @RequestMapping(method = RequestMethod.GET, value = "/start")
        public void launch() {
            this.processEngine.getRuntimeService()
                    .startProcessInstanceByKey("asyncProcess");
        }
    }

    // requests on this channel are _FROM_ Activiti process ActivityBehavior _TO_ Spring Integration handler
    @Bean
    DirectChannel delegate() {
        return new DirectChannel();
    }

    // requests on this channel are _FROM_ Spring MVC REST controller _TO_ Activiti process
    @Bean
    DirectChannel resume() {
        return new DirectChannel();
    }

    @Bean
    ReceiveTaskActivityBehavior gateway() {

        MessageChannel channel = this.delegate();

        return new ReceiveTaskActivityBehavior() {
            @Override
            public void execute(ActivityExecution execution) throws Exception {

                Message<?> executionMessage = MessageBuilder
                        .withPayload(execution)
                        .setHeader("executionId", execution.getId())
                        .build();

                channel.send(executionMessage);
            }
        };
    }

    // from Activiti process to Spring Integration
    @Bean
    IntegrationFlow from() {
        return IntegrationFlows.from(this.delegate())
                .handle(msg -> {
                    msg.getHeaders()
                            .keySet()
                            .forEach(k -> {
                                MessageHeaders headers = msg.getHeaders();
                                System.out.println(String.format("%s = %s", k, headers.get(k)));
                            });
                    System.out.println("executionId = " + msg.getPayload());
                })
                .get();
    }

    // from Spring Integration to Activiti
    @Bean
    IntegrationFlow to(ProcessEngine engine) {
        return IntegrationFlows.from(resume())
                .handle(Activiti.signallingMessageHandler(engine))
                .get();
    }
}