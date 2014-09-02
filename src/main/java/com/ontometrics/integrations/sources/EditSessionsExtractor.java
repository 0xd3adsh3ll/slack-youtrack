package com.ontometrics.integrations.sources;

import com.ontometrics.integrations.configuration.IssueTracker;
import com.ontometrics.integrations.events.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * <p>
 * Provides a means of seeing what things were changed on an {@link com.ontometrics.integrations.events.Issue} and by whom.
 * Does the work of going first through the feed (RSS) and finding out what tickets
 * have been touched, then looking up changes done to each ticket using the REST interface.
 * </p>
 * <p>
 * Note that the things found in the feed are extracted into the classes {@link com.ontometrics.integrations.events.ProcessEvent}
 * and {@link com.ontometrics.integrations.events.ProcessEventChange}, preserving precisely the information found.
 * But then the changes are converted into {@link com.ontometrics.integrations.events.IssueEdit} instances because they
 * are part of a session that contains the information about who updated them and when.
 * </p>
 * User: Rob
 * Date: 8/23/14
 * Time: 10:19 PM
 * <p/>
 * (c) ontometrics 2014, All Rights Reserved
 */
public class EditSessionsExtractor {

    private Logger log = getLogger(EditSessionsExtractor.class);
    private final IssueTracker issueTracker;
    private XMLEventReader eventReader;
    private StreamProvider streamProvider;

    /**
     * Need to talk to the IssueTracker that has the ticket information, and we will probably
     * have to authenticate, hence the streamProvider.
     *
     * @param issueTracker   the system that is used to track issues
     * @param streamProvider authenticated access to the feed stream
     */
    public EditSessionsExtractor(IssueTracker issueTracker, StreamProvider streamProvider) {
        this.issueTracker = issueTracker;
        this.streamProvider = streamProvider;
    }

    public List<IssueEditSession> getLatestEdits() throws Exception {
        return getLatestEdits(null);
    }

    /**
     * Provides a means of seeing what things were changed on an {@link com.ontometrics.integrations.events.Issue} and by whom.
     * Gets a list of IssueEditSessions, being sure to only include edits that were made since we last
     * extracted changes.
     *
     * @return all sessions found that occurred after the last edit
     * @throws Exception
     */
    public List<IssueEditSession> getLatestEdits(Date minDate) throws Exception {
        log.debug("edits since: {}", minDate);
        List<IssueEditSession> sessions = new ArrayList<>();
        List<ProcessEvent> events = getLatestEvents(minDate);
        for (ProcessEvent event : events){
            sessions.addAll(getEdits(event, minDate));
        }
        return sessions;
    }

    public List<IssueEditSession> getEdits(final ProcessEvent e, final Date upToDate) throws Exception {
        return streamProvider.openResourceStream(issueTracker.getChangesUrl(e.getIssue()), new InputStreamHandler<List<IssueEditSession>>() {
            @Override
            public List<IssueEditSession> handleStream(InputStream is) throws Exception {
                XMLInputFactory inputFactory = XMLInputFactory.newInstance();
                XMLEventReader eventReader = inputFactory.createXMLEventReader(is);
                //String currentChangeType;
                String currentFieldName = "";
                String oldValue = "", newValue = "";
                String updaterName = "";
                Date updated = null;
                boolean insideChangesTag = false;
                List<IssueEditSession> extractedEdits = new ArrayList<>();
                List<ProcessEventChange> currentChanges = new ArrayList<>();
                List<Comment> newComments = new ArrayList<>();

                while (eventReader.hasNext()) {
                    XMLEvent nextEvent = eventReader.nextEvent();
                    switch (nextEvent.getEventType()) {
                        case XMLStreamConstants.START_ELEMENT:
                            StartElement startElement = nextEvent.asStartElement();
                            String elementName = startElement.getName().getLocalPart();
                            switch (elementName) {
                                case "change":
                                    insideChangesTag = true;
                                    break;
                                case "field":
                                    currentFieldName = nextEvent.asStartElement().getAttributeByName(new QName("", "name")).getValue();
                                    //currentChangeType = nextEvent.asStartElement().getAttributes().next().toString();
                                    //log.info("found field named: {}: change type: {}", currentFieldName, currentChangeType);
                                    break;
                                case "comment":
//                                    if (!insideChangesTag) { // sadly, comment tags can appear in changes, want to ignore
                                        Comment newComment = extractCommentFromStream(nextEvent.asStartElement());
                                        if (upToDate == null || newComment.getCreated().after(upToDate)) {
                                            newComments.add(newComment);
                                        }
//                                    }
                                    break;
                                case "created":
                                    currentFieldName = "created";
                                    break;
                                case "updaterFullName":
                                    currentFieldName = "creator";
                                    break;
                                default:
                                    String elementText;
                                    try {
                                        elementText = eventReader.getElementText();
                                        switch (elementName) {
                                            case "newValue":
                                                newValue = elementText;
                                                break;
                                            case "oldValue":
                                                oldValue = elementText;
                                                break;
                                            case "value":
                                                if (currentFieldName.equals("updaterName")) {
                                                    updaterName = elementText;
                                                } else if (currentFieldName.equals("updated")) {
                                                    updated = new Date(Long.parseLong(elementText));
                                                }
                                        }
                                    } catch (Exception e) {
                                        //no text..
                                    }
                                    break;
                            }
                            break;

                        case XMLStreamConstants.END_ELEMENT:
                            EndElement endElement = nextEvent.asEndElement();
                            String tagName = endElement.getName().getLocalPart();
                            switch (tagName){
                                case "field":
                                    if (newValue.length() > 0) {
                                        //include only non-processed changes
                                        if (upToDate == null || updated.after(upToDate)) {
                                            if (currentFieldName.equals("resolved")){
                                                newValue = new Date(Long.parseLong(newValue)).toString();
                                            }
                                            ProcessEventChange processEventChange = new ProcessEventChange.Builder()
                                                    .updater(updaterName)
                                                    .updated(updated)
                                                    .field(StringUtils.trim(currentFieldName))
                                                    .priorValue(StringUtils.trim(oldValue))
                                                    .currentValue(StringUtils.trim(newValue))
                                                    .build();

                                            currentChanges.add(processEventChange);
                                        }
                                        currentFieldName = "";
                                        oldValue = "";
                                        newValue = "";

                                    }
                                    break;
                                case "change":
                                    if (upToDate == null || updated.after(upToDate) || newComments.size() > 0) {
                                        log.debug("upToDate: {} updated: {}", upToDate, updated);
                                        List<IssueEdit> edits = buildIssueEdits(currentChanges);
                                        IssueEditSession session = null;
                                        if (edits.size()==0 && newComments.size() > 0){
                                            Comment firstComment = newComments.get(0);
                                            session = new IssueEditSession.Builder()
                                                    .updater(firstComment.getAuthor())
                                                    .updated(firstComment.getCreated())
                                                    .issue(e.getIssue())
                                                    .comments(newComments)
                                                    .build();
                                        } else {
                                            session = new IssueEditSession.Builder()
                                                    .updater(updaterName)
                                                    .updated(updated)
                                                    .issue(e.getIssue())
                                                    .changes(edits)
                                                    .comments(newComments)
                                                    .build();
                                        }
                                        extractedEdits.add(session);
                                    }
                                    currentChanges.clear();
                                    newComments.clear();
                                    insideChangesTag = false;
                                    break;
                            }
                            break;

                    }
                }
                if (extractedEdits.isEmpty()){
                    if (upToDate == null || updated.after(upToDate)){
                        if (newComments.isEmpty()) {
                            Issue newIssue = new Issue.Builder()
                                    .projectPrefix(e.getIssue().getPrefix())
                                    .id(e.getIssue().getId())
                                    .created(updated)
                                    .creator(updaterName)
                                    .description(e.getIssue().getDescription())
                                    .title(e.getIssue().getTitle())
                                    .link(e.getIssue().getLink())
                                    .build();
                            IssueEditSession session = new IssueEditSession.Builder()
                                    .updater(updaterName)
                                    .updated(updated)
                                    .issue(newIssue)
                                    .build();
                            extractedEdits.add(session);
                        } else {
                            Comment firstComment = newComments.get(0);
                            IssueEditSession session = new IssueEditSession.Builder()
                                    .updater(firstComment.getAuthor())
                                    .updated(firstComment.getCreated())
                                    .issue(e.getIssue())
                                    .comments(newComments)
                                    .build();
                            extractedEdits.add(session);
                        }
                    }
                }
                return extractedEdits;
            }
        });
    }

    private Comment extractCommentFromStream(StartElement commentTag) {
        return new Comment.Builder()
                .author(commentTag.getAttributeByName(new QName("", "authorFullName")).getValue())
                .text(commentTag.getAttributeByName(new QName("","text")).getValue())
                .created(new Date(Long.parseLong(commentTag.getAttributeByName(new QName("", "created")).getValue())))
                .build();
    }

    private List<IssueEdit> buildIssueEdits(List<ProcessEventChange> changes) {
        List<IssueEdit> edits = new ArrayList<>(changes.size());
        for (ProcessEventChange change : changes){
            edits.add(new IssueEdit.Builder()
                            .issue(change.getIssue())
                            .field(change.getField())
                            .priorValue(change.getPriorValue())
                            .currentValue(change.getCurrentValue())
                            .build());
        }
        return edits;
    }

    /**
     *
     * @return all ProcessEvents available in the feed (not limited by date)
     * @throws Exception
     */
    public List<ProcessEvent> getLatestEvents() throws Exception {
        return getLatestEvents(null);
    }

    /**
     * Once we have this open, we should make sure that we are not resending events we have already seen.
     *
     * @return the last event that was returned to the user of this class
     */
    public List<ProcessEvent> getLatestEvents(final Date minDate) throws Exception {
        return streamProvider.openResourceStream(issueTracker.getFeedUrl(), new InputStreamHandler<List<ProcessEvent>>() {
            @Override
            public List<ProcessEvent> handleStream(InputStream is) throws Exception {
                byte[] buf = IOUtils.toByteArray(is);
                ByteArrayInputStream bas = new ByteArrayInputStream(buf);
                LinkedList<ProcessEvent> events = new LinkedList<>();
                try {
                    XMLInputFactory inputFactory = XMLInputFactory.newInstance();
                    eventReader = inputFactory.createXMLEventReader(bas);
                    DateFormat dateFormat = createEventDateFormat();
                    while (eventReader.hasNext()) {
                        XMLEvent nextEvent = eventReader.nextEvent();
                        switch (nextEvent.getEventType()) {
                            case XMLStreamConstants.START_ELEMENT:
                                StartElement startElement = nextEvent.asStartElement();
                                String elementName = startElement.getName().getLocalPart();
                                if (elementName.equals("item")) {
                                    //todo: decide if we have to swallow exception thrown by attempt of single event extraction.
                                    //If we swallow it, we have at least report the problem
                                    ProcessEvent event = extractEventFromStream(dateFormat);
                                    if (minDate ==null || event.getPublishDate().after(minDate)) {
                                        //we are adding only events with date after deployment date
                                        events.addFirst(event);
                                    }
                                }
                        }
                    }
                } catch (XMLStreamException e) {
                    throw new IOException("Failed to process XML: content is\n"+new String(buf), e);
                }
                return events;
            }
        });
    }

    private ProcessEvent extractEventFromStream(DateFormat dateFormat) throws Exception {
        String prefix;
        int issueNumber;
        String currentTitle = "", currentLink = "", currentDescription = "";
        Date currentPublishDate = null;
        eventReader.nextEvent();
        StartElement titleTag = eventReader.nextEvent().asStartElement(); // start title tag
        if ("title".equals(titleTag.getName().getLocalPart())){
            currentTitle = eventReader.getElementText();
            eventReader.nextEvent(); // eat end tag
            eventReader.nextEvent();
            currentLink = eventReader.getElementText();
            eventReader.nextEvent(); eventReader.nextEvent();
            currentDescription = eventReader.getElementText().replace("\n", "").trim();
            eventReader.nextEvent(); eventReader.nextEvent();
            currentPublishDate = dateFormat.parse(getEventDate(eventReader.getElementText()));
        }
        String t = currentTitle;
        prefix = t.substring(0, t.indexOf("-"));
        issueNumber = Integer.parseInt(t.substring(t.indexOf("-")+1, t.indexOf(":")));
        Issue issue = new Issue.Builder().id(issueNumber).projectPrefix(StringUtils.trim(prefix))
                .title(StringUtils.trim(currentTitle))
                .description(StringUtils.trim(currentDescription))
                .link(new URL(StringUtils.trim(currentLink)))
                .build();
        ProcessEvent event = new ProcessEvent.Builder()
                .issue(issue)
                .published(currentPublishDate)
                .build();
        log.debug("process event extracted and built: {}", event);
        return event;
    }

    private DateFormat createEventDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat;
    }

    private String getEventDate(String date) {
        String UTC = "UT";
        if (date.contains("UT")) {
            return date.substring(0, date.indexOf(UTC));
        }
        return date;
    }

}
