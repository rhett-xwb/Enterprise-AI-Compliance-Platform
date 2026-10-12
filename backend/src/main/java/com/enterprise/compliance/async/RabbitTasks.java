package com.enterprise.compliance.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.async.enabled",havingValue="true")
public class RabbitTasks {
    public static final String QUEUE="compliance.document.tasks", DEAD="compliance.document.dead";
    private final AsyncJobs jobs; private final AsyncProcessor processor; private final RabbitTemplate rabbit; private final ObjectMapper json;
    public RabbitTasks(AsyncJobs jobs,AsyncProcessor processor,RabbitTemplate rabbit,ObjectMapper json,
                       @org.springframework.beans.factory.annotation.Value("${app.parser.timeout-seconds:190}") int timeout) {
        if(timeout>500) throw new IllegalArgumentException("Async parser timeout must be <= 500 seconds");
        this.jobs=jobs; this.processor=processor; this.rabbit=rabbit; this.json=json;
        rabbit.setMandatory(true);
    }
    @Bean public Declarables taskQueues() {
        return new Declarables(QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DEAD).build(),QueueBuilder.durable(DEAD).build());
    }
    @Scheduled(fixedDelayString="${app.async.poll-ms:1000}")
    public void dispatch() {
        jobs.recover();
        for(var task:jobs.dispatchable()) {
            try {
                var correlation=new CorrelationData(task.id()+":"+task.token()+":"+java.util.UUID.randomUUID());
                var properties=new MessageProperties(); properties.setContentType("application/json");
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); properties.setMessageId(task.id()+":"+task.token());
                rabbit.send("",QUEUE,new Message(json.writeValueAsBytes(task),properties),correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Message not accepted");
                jobs.dispatched(task);
            } catch(Exception ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                jobs.deliveryFailed(task);
            }
        }
    }
    @RabbitListener(queues=QUEUE,concurrency="1")
    public void receive(Message message) {
        AsyncJobs.Message task;
        try {
            if(message.getBody().length>256) throw new IllegalArgumentException();
            task=json.readValue(message.getBody(),AsyncJobs.Message.class);
            if(task.id()<1 || task.token()==null || !task.token().matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
        } catch(Exception ex) { throw new AmqpRejectAndDontRequeueException("INVALID_TASK_MESSAGE"); }
        processor.process(task);
    }
}
