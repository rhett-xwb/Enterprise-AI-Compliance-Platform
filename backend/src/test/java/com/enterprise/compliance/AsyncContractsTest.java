package com.enterprise.compliance;

import com.enterprise.compliance.async.*;
import com.enterprise.compliance.document.ParsingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncContractsTest {
    // Mockito matchers return null placeholders consumed by mock interception.
    @SuppressWarnings({"null", "unchecked"})
    @Test void redisUsesNamespacedVersionHashTtlAndFallsBackOnFailure() throws Exception {
        var redis=mock(StringRedisTemplate.class); ValueOperations<String,String> values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        var json=new ObjectMapper(); var cache=new SourceCache(true,"isolated",redis,json);
        var sources=List.of(new ParsingStore.Source(0,1,"第 1 页",null,"条款正文","TEXT",null,List.of()));
        cache.put(12,"abc",sources); verify(values).set(eq("isolated:sources:v1:12:abc"),eq(json.writeValueAsString(sources)),eq(Duration.ofMinutes(5)));
        when(values.get("isolated:sources:v1:12:abc")).thenReturn(json.writeValueAsString(sources)); assertEquals(sources,cache.get(12,"abc"));
        when(values.get(anyString())).thenThrow(new RuntimeException("down")); assertNull(cache.get(12,"abc")); assertEquals(1L,cache.metrics().get("errors"));
        var disabled=new SourceCache(false,"isolated",redis,json); clearInvocations(redis); assertNull(disabled.get(12,"abc")); disabled.put(12,"abc",sources); verifyNoInteractions(redis);
    }
    // Mockito matchers return null placeholders; runtime null checks would break stubbing.
    @SuppressWarnings("null")
    @Test void publisherMarksOutboxSentOnlyAfterPositiveConfirmAndNoReturn() {
        var jobs=mock(AsyncJobs.class); var processor=mock(AsyncProcessor.class); var template=mock(RabbitTemplate.class);
        var message=new AsyncJobs.Message(1,UUID.randomUUID().toString()); when(jobs.dispatchable()).thenReturn(List.of(message));
        var tasks=new RabbitTasks(jobs,processor,template,new ObjectMapper(),190);
        doAnswer(a->{ a.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true,null)); return null; }).when(template).send(eq(""),eq(RabbitTasks.QUEUE),any(Message.class),any(CorrelationData.class));
        tasks.dispatch(); verify(jobs).dispatched(message); verify(jobs,never()).deliveryFailed(any());
        clearInvocations(jobs); doThrow(new RuntimeException("broker down")).when(template).send(eq(""),eq(RabbitTasks.QUEUE),any(Message.class),any(CorrelationData.class)); tasks.dispatch(); verify(jobs).deliveryFailed(message); verify(jobs,never()).dispatched(any());
        clearInvocations(jobs); doAnswer(a->{ a.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(false,"nack")); return null; }).when(template).send(eq(""),eq(RabbitTasks.QUEUE),any(Message.class),any(CorrelationData.class)); tasks.dispatch(); verify(jobs).deliveryFailed(message); verify(jobs,never()).dispatched(any());
        clearInvocations(jobs); doAnswer(a->{ var c=a.<CorrelationData>getArgument(3); c.setReturned(new ReturnedMessage(a.getArgument(2),312,"NO_ROUTE","",RabbitTasks.QUEUE)); c.getFuture().complete(new CorrelationData.Confirm(true,null)); return null; }).when(template).send(eq(""),eq(RabbitTasks.QUEUE),any(Message.class),any(CorrelationData.class)); tasks.dispatch(); verify(jobs).deliveryFailed(message); verify(jobs,never()).dispatched(any());
    }
    @Test void malformedMessagesAreRejectedAndValidMessagesUseDurableProcessor() throws Exception {
        var processor=mock(AsyncProcessor.class); var json=new ObjectMapper(); var tasks=new RabbitTasks(mock(AsyncJobs.class),processor,mock(RabbitTemplate.class),json,190);
        assertThrows(AmqpRejectAndDontRequeueException.class,()->tasks.receive(new Message("malformed".getBytes(),new MessageProperties())));
        var m=new AsyncJobs.Message(2,UUID.randomUUID().toString()); tasks.receive(new Message(json.writeValueAsBytes(m),new MessageProperties())); verify(processor).process(m);
        assertThrows(IllegalArgumentException.class,()->new RabbitTasks(mock(AsyncJobs.class),processor,mock(RabbitTemplate.class),json,501));
    }
}
