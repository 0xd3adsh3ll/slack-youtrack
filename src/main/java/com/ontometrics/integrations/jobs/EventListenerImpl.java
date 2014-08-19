package com.ontometrics.integrations.jobs;

import com.ontometrics.integrations.configuration.ConfigurationFactory;
import com.ontometrics.integrations.configuration.EventProcessorConfiguration;
import com.ontometrics.integrations.sources.*;
import org.apache.commons.configuration.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created on 8/18/14.
 *
 */
public class EventListenerImpl implements EventListener {
    private static final Logger log = LoggerFactory.getLogger(EventListenerImpl.class);

    public static final String TOKEN_KEY = "token";
    public static final String TEXT_KEY = "text";
    public static final String CHANNEL_KEY = "channel";

    private final ChannelMapper channelMapper;
    private SourceEventMapper sourceEventMapper;
    public static final String SLACK_URL = "https://slack.com/api/";
    public static final String CHANNEL_POST_PATH = "chat.postMessage";

    /**
     * TODO rework, so it can authenticate into sourceUrl (we got http-error #401)
     * @param inputStreamProvider url to read list of events from
     * @param channelMapper channelMapper
     */
    public EventListenerImpl(InputStreamProvider inputStreamProvider, ChannelMapper channelMapper) {

        this.channelMapper = channelMapper;
        if(inputStreamProvider == null || channelMapper == null) throw new IllegalArgumentException("You must provide sourceURL and channelMapper.");


        sourceEventMapper = new SourceEventMapper(inputStreamProvider);
        sourceEventMapper.setLastEvent(EventProcessorConfiguration.instance().loadLastProcessedEvent());
    }

    @Override
    public int checkForNewEvents() throws IOException {
        //get events
        List<ProcessEvent> events = sourceEventMapper.getLatestEvents();

        final AtomicInteger processedEventsCount = new AtomicInteger(0);
        try {
            events.stream().forEach(e -> {
                processedEventsCount.incrementAndGet();
                List<ProcessEventChange> changes = sourceEventMapper.getChanges(e, getLastEventChangeDate(e));
                postEventChangesToStream(e, changes, channelMapper.getChannel(e));
            });
        } catch (Exception ex) {
            log.error("Got error while processing event changes feed", ex);
        }

        return processedEventsCount.get();
    }

    private void postEventChangesToStream(ProcessEvent event, List<ProcessEventChange> changes, String channel) {
        if (changes.isEmpty()) {
            //todo: post creation of the issue
        } else {
            //todo: make a post based on the collection of changes
            EventProcessorConfiguration.instance()
                    .saveEventChangeDate(event, changes.get(changes.size() - 1).getUpdated());
        }
        sourceEventMapper.setLastEvent(event);
        try {
            EventProcessorConfiguration.instance().saveLastProcessEvent(event);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Failed to update last processed event", e);
        }
    }

    private Date getLastEventChangeDate(ProcessEvent event) {
        return EventProcessorConfiguration.instance().getEventChangeDate(event);
    }

    private void postEventToChannel(ProcessEvent event, String channel){
        log.info("posting event {}.", event.toString());
        Client client = ClientBuilder.newClient();

        WebTarget slackApi = client.target(SLACK_URL).path(CHANNEL_POST_PATH)
                .queryParam(TOKEN_KEY, ConfigurationFactory.get().getString("SLACK_AUTH_TOKEN"))
                .queryParam(TEXT_KEY, getText(event))
                .queryParam(CHANNEL_KEY, "#" + channel);

        Invocation.Builder invocationBuilder = slackApi.request(MediaType.APPLICATION_JSON);
        Response response = invocationBuilder.get();

        log.info("response code: {} response: {}", response.getStatus(), response.readEntity(String.class));

    }

    private String getText(ProcessEvent event){
        StringBuilder builder = new StringBuilder();
        String title = event.getTitle();
        title = title.replace(event.getID(), "");
        builder.append("<").append(event.getLink()).append("|").append(event.getID()).append(">").append(title);
        return builder.toString();
    }

    public SourceEventMapper getSourceEventMapper() {
        return sourceEventMapper;
    }
}
