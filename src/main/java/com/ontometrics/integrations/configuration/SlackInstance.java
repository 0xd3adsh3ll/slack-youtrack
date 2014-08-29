package com.ontometrics.integrations.configuration;

import com.ontometrics.integrations.events.Comment;
import com.ontometrics.integrations.events.Issue;
import com.ontometrics.integrations.events.IssueEdit;
import com.ontometrics.integrations.events.IssueEditSession;
import com.ontometrics.integrations.sources.ChannelMapper;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Created by Rob on 8/23/14.
 * Copyright (c) ontometrics, 2014 All Rights Reserved
 */
public class SlackInstance implements ChatServer {

    private Logger log = getLogger(SlackInstance.class);
    public static final String BASE_URL = "https://slack.com";
    public static final String API_PATH = "api";
    public static final String CHANNEL_POST_PATH = "chat.postMessage";
    public static final String TOKEN_KEY = "token";
    public static final String TEXT_KEY = "text";
    public static final String CHANNEL_KEY = "channel";

    private final ChannelMapper channelMapper;

    public SlackInstance(Builder builder) {
        channelMapper = builder.channelMapper;
    }

    public static class Builder {

        private ChannelMapper channelMapper;

        public Builder channelMapper(ChannelMapper channelMapper){
            this.channelMapper = channelMapper;
            return this;
        }

        public SlackInstance build(){
            return new SlackInstance(this);
        }
    }

    @Override
    public void postIssueCreation(Issue issue) {
        postToChannel(channelMapper.getChannel(issue), buildNewIssueMessage(issue));
    }

    @Override
    public void post(IssueEditSession issueEditSession){
        String channel = channelMapper.getChannel(issueEditSession.getIssue());
        postToChannel(channel, buildSessionMessage(issueEditSession));
        
    }

    private void postToChannel(String channel, String message) {
        log.info("posting message {} to channel: {}.", message, channel);
        Client client = ClientBuilder.newClient();

        WebTarget slackApi = client.target(BASE_URL).path(String.format("%s/%s", API_PATH, CHANNEL_POST_PATH))
                .queryParam(TOKEN_KEY, ConfigurationFactory.get().getString("PROP.SLACK_AUTH_TOKEN"))
                .queryParam(TEXT_KEY, processMessage(message))
                .queryParam(CHANNEL_KEY, "#" + channel);

        Invocation.Builder invocationBuilder = slackApi.request(MediaType.APPLICATION_JSON);
        Response response = invocationBuilder.get();

        log.info("response code: {} response: {}", response.getStatus(), response.readEntity(String.class));

    }

    private String processMessage(String message) {
        return StringUtils.replaceChars(message, "{}", "[]");
    }

    protected String buildSessionMessage(IssueEditSession session) {
        StringBuilder s = new StringBuilder(String.format("*%s*", session.getUpdater()));
        String action = session.getComments().size() > 0 ? "commented on " : "updated";
        s.append(String.format(" %s %s: ", action, MessageFormatter.getIssueLink(session.getIssue())));
        s.append(MessageFormatter.getTitleWithoutIssueID(session.getIssue()));
        s.append(System.lineSeparator());
        int changeCounter = 0;
        for (IssueEdit edit : session.getChanges()){
            s.append(edit.toString());
            if (changeCounter++ < session.getChanges().size()-1){
                s.append(System.lineSeparator());
            }
        }
        for (Comment comment : session.getComments()){
            s.append(comment.getText());
        }
        return s.toString();
    }

    protected String buildNewIssueMessage(Issue newIssue){
        return String.format("*%s*", newIssue.getCreator()) + " created " + MessageFormatter.getIssueLink(newIssue) + MessageFormatter.getTitleWithoutIssueID(newIssue);
    }

    private static class MessageFormatter {
        static String getIssueLink(Issue issue){
            return String.format("<%s|%s-%d>", issue.getLink(), issue.getPrefix(), issue.getId());
        }

        static String getTitleWithoutIssueID(Issue issue){
            return issue.getTitle().substring(issue.getTitle().indexOf(":") + 1);
        }
    }

}

