package ontometrics.jobs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ontometrics.integrations.configuration.*;
import com.ontometrics.integrations.events.Issue;
import com.ontometrics.integrations.events.IssueEditSession;
import com.ontometrics.integrations.events.ProcessEvent;
import com.ontometrics.integrations.jobs.EventListenerImpl;
import com.ontometrics.integrations.sources.ChannelMapper;
import com.ontometrics.integrations.sources.EditSessionsExtractor;
import com.ontometrics.util.DateBuilder;
import ontometrics.test.util.TestUtil;
import ontometrics.test.util.UrlStreamProvider;
import org.apache.commons.configuration.ConfigurationException;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;


/**
 * EventListenerImplTest.java

 */
public class EventListenerImplTest {

    @Test
    public void testThatSourceEventMapperCorrectlyInitializedOnFirstStart() throws ConfigurationException {
        EventProcessorConfiguration configuration = EventProcessorConfiguration.instance();
        configuration.clearLastProcessEvent();
        EventListenerImpl eventListener = createEventListener();
        assertThat(eventListener.getEditSessionsExtractor(), notNullValue());
        assertThat(eventListener.getEditSessionsExtractor().getLastEventDate(), nullValue());
    }

    @Test
    public void testThatSourceEventMapperCorrectlyInitializedWithExistingConfiguration() throws ConfigurationException, MalformedURLException {

        EventProcessorConfiguration configuration = EventProcessorConfiguration.instance();
        Issue issue = new Issue.Builder().projectPrefix("ASOC").id(28)
                .title("ASOC-28: title")
                .link(new URL("http://ontometrics.com:8085/issue/ASOC-28"))
                .build();
        ProcessEvent processEvent = new ProcessEvent.Builder().issue(issue).published(new Date()).build();
        configuration.saveLastProcessedEventDate(processEvent.getPublishDate());

        EventListenerImpl eventListener = createEventListener();
        assertThat(eventListener.getEditSessionsExtractor(), notNullValue());
        assertThat(eventListener.getEditSessionsExtractor().getLastEventDate(), notNullValue());
//        assertThat(eventListener.getEditSessionsExtractor().getLastEventDate().getIssue().getLink(), is(processEvent.getIssue().getLink()));
//        assertThat(eventListener.getEditSessionsExtractor().getLastEventDate().getPublishDate(), is(processEvent.getPublishDate()));

    }

    @Test
    /**
     * Tests that all new issue edits are fetched by {@link com.ontometrics.integrations.jobs.EventListenerImpl#checkForNewEvents()}
     *
     * Current last issue date is <code>T0</code>.
     * Issue 1 has changes with time line: (T-1), T1, T3
     * Issue 2 has changes with time line: (T-2), T2, T5
     * Relation between those timestamps are as follows
     *                        T0
     * Issue 1: (T-1)              T1        T3
     * Issue 2:       (T-2)             T2        T5
     *  (T-1) < (T-2) < T0 < T1 < T2 < T3 < T5
     *
     * Expected edits for issue 1: (T1) and (T3)
     * Expected edits for issue 2: (T2) and (T5)
     */
    public void testThatAllNewEditsAreFetched() throws Exception {
        final Date T_MINUS_2 = new Date(1404927519000L);
        final Date T1 = new Date(1404927529000L);
        final Date T0 = new Date((T_MINUS_2.getTime() + T1.getTime()) / 2);
        final Date T2 = new Date(1404927539000L);
        final Date T3 = new Date(1404927549000L);
        final Date T5 = new Date(1404927559000L);

        final AtomicInteger issueOrder = new AtomicInteger(0);
        EventProcessorConfiguration.instance().clear();
        EventProcessorConfiguration.instance().saveLastProcessedEventDate(T0);
        EventProcessorConfiguration.instance().setDeploymentTime(T0);
        MockIssueTracker mockYouTrackInstance = new MockIssueTracker("/feeds/issues-feed-rss.xml", null) {
            @Override
            public URL getChangesUrl(Issue issue) {
                if (issueOrder.get() == 0) {
                    //for issue 1 return first set of changes
                    return TestUtil.getFileAsURL("/feeds/issue1-timeline-changes.xml");
                }
                //for issue 2 return second set of changes
                return TestUtil.getFileAsURL("/feeds/issue2-timeline-changes.xml");
            }
        };
        EditSessionsExtractor sessionsExtractor = new EditSessionsExtractor(mockYouTrackInstance,
                UrlStreamProvider.instance()) {
            @Override
            public List<IssueEditSession> getEdits(ProcessEvent e, Date upToDate) throws Exception {
                List<IssueEditSession> sessions = super.getEdits(e, upToDate);
                if (issueOrder.get() == 0) {
                    //asserting that all edits for issue 1 are fetched (T1 and T3)
                    assertThat(sessions.size(), is(2));
                    assertThat(sessions.get(0).getUpdated(), is(T1));
                    assertThat(sessions.get(1).getUpdated(), is(T3));
                } else {
                    //asserting that all edits for issue 2 are fetched (T2 and T5)
                    assertThat(sessions.size(), is(2));
                    assertThat(sessions.get(0).getUpdated(), is(T2));
                    assertThat(sessions.get(1).getUpdated(), is(T5));
                }
                //issue is processed, edits for it are loaded, increasing issue #
                issueOrder.incrementAndGet();
                return sessions;
            }

            @Override
            /**
             * Emulates getting of only two latest events with publish date more than {@link T0}
             */
            public List<ProcessEvent> getLatestEvents() throws Exception {
                List<ProcessEvent> events = super.getLatestEvents().subList(0, 2);
                events.get(0).setPublishDate(T3);
                events.get(1).setPublishDate(T5);
                this.setLastEventDate(T0);
                return events;
            }
        };
        EventListenerImpl eventListener = new EventListenerImpl(sessionsExtractor, new EmptyChatServer());
        int events = eventListener.checkForNewEvents();
        //asserting that there are 2 issues processed
        assertThat(issueOrder.get(), is(2));
        assertThat(events, is(2));
    }



    @Test
    /**
     *             T0
     * Issue-1:            T1        +T3
     * Issue-2:                 T2
     *
     * t0 - initial LastEventDate
     * On first call to {@link com.ontometrics.integrations.jobs.EventListener#checkForNewEvents()} we'll get list of issues
     * Issue1(T1) and Issue2(T2), however on {@link com.ontometrics.integrations.sources.EditSessionsExtractor#getEdits(com.ontometrics.integrations.events.ProcessEvent)}
     * for Issue-1 it will return changes with time T1 and T3
     * On first session of changes Issue-2 will return single event with T2
     * On completion of first session last issue date will be set to T2

     * On second read for Issue-1 we will got T1 and T3, however as long we reported T3 before it should not be reported again
     *
     * Note: T3 is slightly higher than T2, between {@link com.ontometrics.integrations.sources.EditSessionsExtractor#getLatestEvents}
     * and getting changes to concrete issue (may happen for longer list of issues to process)
     * T0 < T1 < T2 < T3
     */
    public void testThatIssueEditSessionIsNotPostedOnSecondCallIfPostedOnfFirst() throws Exception {

        final Issue issue1 = new Issue.Builder().projectPrefix("ISSUE").id(1).build();
        final Issue issue2 = new Issue.Builder().projectPrefix("ISSUE").id(2).build();
        MockIssueTracker mockIssueTracker = new MockIssueTracker("/feeds/issues-feed-rss.xml",
                ImmutableMap.<Issue, String>builder().put(issue1, "/feeds/issue-changes-t1-t3.xml")
                        .put(issue2, "/feeds/issue-changes-t2.xml")
                        .build()
                );
        EventProcessorConfiguration.instance().clear();
        EventProcessorConfiguration.instance().saveLastProcessedEventDate(new Date(0)); //set it to 1970, to not depend on it
        //Issue-1 with T1 and Issue-2 with T2 will be returned on first call  to getLatestEvents()
        final List<ProcessEvent> firstCall = ImmutableList.<ProcessEvent> builder()
                .add(new ProcessEvent.Builder().issue(issue1).published(new Date(1404927516756L)).build()) //T1
                .add(new ProcessEvent.Builder().issue(issue2).published(new Date(1405025458837L)).build()) //T2
                .build();
        //Issue-1 with T1 and Issue-2 with T3 will be returned on second call to getLatestEvents()
        final List<ProcessEvent> secondCall = ImmutableList.<ProcessEvent> builder()
                .add(new ProcessEvent.Builder().issue(issue1).published(new Date(1406227765001L)).build())  //T3
                .build();

        final AtomicInteger executionCount = new AtomicInteger(1);
        //create mocked editSessionsExtractor#getLatestEvents() depending on execution count
        EditSessionsExtractor editSessionsExtractor = new EditSessionsExtractor(mockIssueTracker,
                UrlStreamProvider.instance()) {
            @Override
            public List<ProcessEvent> getLatestEvents() throws Exception {
                if (executionCount.get() == 1) {
                    return firstCall;
                } else if (executionCount.get() == 2) {
                    return secondCall;
                }
                throw new IllegalStateException("only first and second calls are supported");
            }
        };

        //create EventListenerImpl with CheckDuplicateMessagesChatServer to check there are no duplicates
        EventListenerImpl eventListener = new EventListenerImpl(editSessionsExtractor,
                new CheckDuplicateMessagesChatServer());

        eventListener.checkForNewEvents();

        executionCount.incrementAndGet();

        eventListener.checkForNewEvents();
    }

    @Test
    /**
     * Tests that when a new issue (issue which was not reported in the RSS before) is posted to the chat server
     * as a new issue, e.g. {@link com.ontometrics.integrations.configuration.ChatServer#postIssueCreation(com.ontometrics.integrations.events.Issue)} is called
     */
    public void testThatIssueWithNoChangesWhichWasNotReportedBeforeCausesPostingOfIssueCreation() throws Exception {
        IssueTracker mockIssueTracker = new SimpleMockIssueTracker("/feeds/issues-feed-rss.xml",
                "/feeds/empty-issue-changes.xml");
        clearData();
        EventProcessorConfiguration.instance().setDeploymentTime(new DateBuilder().year(2013).build());

        final AtomicInteger createdIssuePostsCount = new AtomicInteger();

        EventListenerImpl eventListener = new EventListenerImpl(new EditSessionsExtractor(mockIssueTracker,
                UrlStreamProvider.instance()), new EmptyChatServer() {
            @Override
            public void postIssueCreation(Issue issue) {
                createdIssuePostsCount.incrementAndGet();
            }
        });
        int events = eventListener.checkForNewEvents();
        assertThat(events, not(is(0)));
        assertThat(events, is(createdIssuePostsCount.get()));
    }

    @Test
    /**
     * Tests that when a new issue (issue which was not reported in the RSS before) is posted to the chat server,
     * then after it's detected in the feed next time with no changes (for example comment has been added),
     * it is not posted as created issue to Slack again
     */
    public void testThatAfterIssueCreationIsPostedItIsNotPostedAgain() throws Exception {
        IssueTracker mockIssueTracker = new SimpleMockIssueTracker("/feeds/issues-feed-rss.xml",
                "/feeds/empty-issue-changes.xml");
        clearData();
        EventProcessorConfiguration.instance().setDeploymentTime(new DateBuilder().year(2013).build());

        //just processing feed with the list of events, storing the reference to one of the events
        final ObjectWrapper<ProcessEvent> event = new ObjectWrapper<>();
        new EventListenerImpl(new EditSessionsExtractor(mockIssueTracker,
                UrlStreamProvider.instance()) {
            @Override
            public List<ProcessEvent> getLatestEvents() throws Exception {
                List<ProcessEvent> events = super.getLatestEvents();
                event.set(events.get(0));
                return events;
            }
        }, new EmptyChatServer()).checkForNewEvents();

        // now we are emulating situation where we get one of the events we already processed from the feed
        // since it has already been processed before, application must not report issue creation for this event again
        new EventListenerImpl(new EditSessionsExtractor(mockIssueTracker,
                UrlStreamProvider.instance()) {
            @Override
            public List<ProcessEvent> getLatestEvents() throws Exception {
                return Arrays.asList(event.get());
            }
        }, new EmptyChatServer() {
            @Override
            public void postIssueCreation(Issue issue) {
                fail("Issue creation has already been posted");
            }
        }).checkForNewEvents();

    }

    private void clearData() throws ConfigurationException {
        EventProcessorConfiguration.instance().clear();
    }


    public static class MockIssueTracker extends SimpleMockIssueTracker {
        private Map<Issue,String> changesUrlMap;

        public MockIssueTracker(String feedUrl, Map<Issue, String> changesUrlMap) {
            super(feedUrl, null);
            this.changesUrlMap = changesUrlMap;
        }

        @Override
        public URL getChangesUrl(Issue issue) {
            return TestUtil.getFileAsURL(changesUrlMap.get(issue));
        }

    }


    private EventListenerImpl createEventListener() {
        return new EventListenerImpl(UrlStreamProvider.instance(), new SlackInstance.Builder()
                .channelMapper(createChannelMapper()).build());
    }


    /**
     * @return channel mapper with default projects: ASOC, Job Spider and Dminder
     */
    private static ChannelMapper createChannelMapper() {
        return new ChannelMapper.Builder()
                .defaultChannel("process")
                .addMapping("ASOC", "vixlet")
                .addMapping("HA", "jobspider")
                .addMapping("DMAN", "dminder")
                .build();
    }

}
