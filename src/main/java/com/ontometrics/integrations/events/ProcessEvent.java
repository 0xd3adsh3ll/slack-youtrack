package com.ontometrics.integrations.events;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * User: robwilliams
 * Date: 7/15/14
 * Time: 8:42 PM
 * <p>
 * (c) ontometrics 2014, All Rights Reserved
 */
public class ProcessEvent {

    private static final String KEY_FIELD_SEPARATOR = "::";

    private final String title;
    private final String description;
    private final Date publishDate;
    private final String link;
    private String issueID;

    public ProcessEvent(Builder builder) {
        issueID = builder.title.substring(0, builder.title.indexOf(":"));
        title = builder.title;
        description = builder.description;
        publishDate = builder.publishDate;
        link = builder.link;
    }

    public static class Builder {

        private String title;
        private String description;
        private Date publishDate;
        private String link;

        public Builder title(String title){
            this.title = title;
            return this;
            }

        public Builder description(String description){
            this.description = description;
            return this;
            }

        public Builder published(Date publishDate){
            this.publishDate = publishDate;
            return this;
            }

        public Builder link(String link){
            this.link = link;
            return this;
            }

        public ProcessEvent build(){
            return new ProcessEvent(this);
        }
    }

    public String getID() {
        return issueID;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Date getPublishDate() {
        return publishDate;
    }

    public String getLink() {
        return link;
    }

    /**
     * @return Unique key of the event: combination of issueID and publish Date
     */
    public String getKey() {
        return issueID + KEY_FIELD_SEPARATOR + createDateFormat().format(publishDate);
    }

    private DateFormat createDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat;
    }

    @Override
    public String toString() {
        return "ProcessEvent{" +
                "title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", publishDate=" + getPublishDate() +
                ", link='" + link + '\'' +
                '}';
    }
}
