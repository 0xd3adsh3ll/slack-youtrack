package ontometrics.integrations.sources;

import com.ontometrics.integrations.events.Issue;
import com.ontometrics.integrations.sources.ChannelMapper;
import com.ontometrics.integrations.sources.ChannelMapperFactory;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * ChannelMapperFactoryTest.java
 */
public class ChannelMapperFactoryTest {

    @Test
    public void canCreateMapperFromConfiguration(){
        checkCanCreatePropertiesConfiguration("");
        checkCanCreatePropertiesConfiguration("prefix.");
    }

    private void checkCanCreatePropertiesConfiguration(String mapperPrefix) {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setListDelimiter(';');
        configuration.setProperty(mapperPrefix + ChannelMapperFactory.CHANNEL_MAPPINGS, "ABC->xyz;XYZ->abc");
        configuration.setProperty(mapperPrefix + ChannelMapperFactory.DEFAULT_CHANNEL, "apple");

        ChannelMapper mapper = ChannelMapperFactory.fromConfiguration(configuration, mapperPrefix);
        assertThat(mapper, notNullValue());
        assertThat(mapper.getChannel(issue("ABC")), is("xyz"));
        assertThat(mapper.getChannel(issue("XYZ")), is("abc"));
        assertThat(mapper.getChannel(issue("microsoft")), is("apple"));
        assertThat(mapper.getChannel(issue("nothing")), is("apple"));
    }

    private static Issue issue(String prefix) {
        return new Issue.Builder().projectPrefix(prefix).build();
    }
}

