package com.github.tvbox.osc.log.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.log.LogEntry;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogRepositoryOrderTest {

    @Test
    public void recentWindowIsPreservedButDisplayedOldestFirst() {
        List<LogEntry> newestFirst = new ArrayList<>();
        for (long id = 500; id >= 201; id--) newestFirst.add(entry(id, id));

        List<LogEntry> chronological = LogRepository.chronologicalPage(newestFirst);

        assertEquals(300, chronological.size());
        for (int i = 0; i < chronological.size(); i++) {
            assertEquals(201L + i, chronological.get(i).id);
            assertSame(newestFirst.get(299 - i), chronological.get(i));
        }
        assertEquals(500L, newestFirst.get(0).id);
        assertEquals(201L, newestFirst.get(299).id);
    }

    @Test
    public void equalTimestampsFollowAscendingInsertionIds() {
        LogEntry first = entry(10, 1234);
        LogEntry second = entry(11, 1234);
        LogEntry third = entry(12, 1234);

        List<LogEntry> chronological = LogRepository.chronologicalPage(
                Arrays.asList(third, second, first));

        assertEquals(Arrays.asList(first, second, third), chronological);
    }

    @Test
    public void sourceListIsNotReorderedOrReused() {
        LogEntry first = entry(1, 100);
        LogEntry second = entry(2, 200);
        List<LogEntry> source = new ArrayList<>(Arrays.asList(second, first));

        List<LogEntry> chronological = LogRepository.chronologicalPage(source);

        assertNotSame(source, chronological);
        assertEquals(Arrays.asList(second, first), source);
        chronological.clear();
        assertEquals(2, source.size());
    }

    @Test
    public void immutableQueryResultsAreSupported() {
        LogEntry first = entry(1, 100);
        LogEntry second = entry(2, 200);
        List<LogEntry> source = Collections.unmodifiableList(Arrays.asList(second, first));

        assertEquals(Arrays.asList(first, second), LogRepository.chronologicalPage(source));
        assertEquals(Arrays.asList(second, first), source);
    }

    @Test
    public void nullAndEmptyKeepTheirQueryMeaning() {
        assertNull(LogRepository.chronologicalPage(null));
        assertTrue(LogRepository.chronologicalPage(Collections.emptyList()).isEmpty());
    }

    @Test
    public void multilineDetailsRemainIntactWithinTheirEntry() {
        LogEntry first = entry(1, 100);
        first.detail = "request\nresolve\nplay";
        LogEntry second = entry(2, 200);
        second.detail = "exception\n    at example.Play.run(Play.java:42)";

        List<LogEntry> chronological = LogRepository.chronologicalPage(
                Arrays.asList(second, first));

        assertSame(first, chronological.get(0));
        assertSame(second, chronological.get(1));
        assertEquals("request\nresolve\nplay", chronological.get(0).detail);
        assertEquals("exception\n    at example.Play.run(Play.java:42)", chronological.get(1).detail);
    }

    @Test
    public void databaseStillSelectsNewestWindowWithStableTieBreaking() throws Exception {
        String dao = readDao();
        String order = "ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset";
        assertTrue(statementFor(dao, "query").contains(order));
        assertTrue(statementFor(dao, "queryByTask").contains(order));
        assertTrue(statementFor(dao, "trimTo").contains(
                "ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET :keep"));
    }

    private static LogEntry entry(long id, long timestamp) {
        LogEntry entry = new LogEntry();
        entry.id = id;
        entry.timestamp = timestamp;
        return entry;
    }

    private static String readDao() throws Exception {
        String path = "log/src/main/java/com/github/tvbox/osc/log/db/LogDao.java";
        File file = new File(path);
        if (!file.isFile()) file = new File("..", path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String statementFor(String source, String method) {
        Matcher declaration = Pattern.compile("\\b(?:List<LogEntry>|int)\\s+" + method + "\\(")
                .matcher(source);
        assertTrue("Missing DAO method: " + method, declaration.find());
        int annotation = source.lastIndexOf("@Query(", declaration.start());
        assertTrue("Missing query annotation: " + method, annotation >= 0);
        return source.substring(annotation, declaration.start());
    }
}
