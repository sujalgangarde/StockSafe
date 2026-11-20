package com.stocksafe.alerts.notify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Claims due notifications (a 2 minute lease), sends them, records the result. Failed ones back off and retry. */
@Component
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final JdbcTemplate jdbc;
    private final NotificationSender sender;

    public NotificationDispatcher(JdbcTemplate jdbc, NotificationSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    public int dispatchOnce() {
        List<Map<String, Object>> due = jdbc.queryForList("select * from notif_claim(50)");
        for (Map<String, Object> n : due) {
            UUID id = (UUID) n.get("id");
            try {
                sender.send((String) n.get("channel"), (String) n.get("recipient"),
                        (String) n.get("subject"), (String) n.get("body"));
                jdbc.queryForList("select notif_mark(?, true, null)", id);
            } catch (Exception e) {
                log.warn("Notification {} failed (attempt {}): {}", id, n.get("attempts"), e.toString());
                jdbc.queryForList("select notif_mark(?, false, ?)", id, e.toString());
            }
        }
        return due.size();
    }
}
