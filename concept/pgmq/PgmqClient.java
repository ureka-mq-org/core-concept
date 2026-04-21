package com.pgmq;

import com.pgmq.domain.PgmqMessage;
import com.pgmq.domain.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgmqClient {

  private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,62}$");

  private final JdbcTemplate jdbcTemplate;

  private static final RowMapper<PgmqMessage> MESSAGE_ROW_MAPPER = (rs, rowNum) ->
      PgmqMessage.builder()
          .msgId(rs.getLong("msg_id"))
          .readCount(rs.getInt("read_ct"))
          .enqueuedAt(rs.getObject("enqueued_at", OffsetDateTime.class))
          .visibilityTimeout(rs.getObject("vt", OffsetDateTime.class))
          .message(rs.getString("message"))
          .build();

  private static final RowMapper<PgmqMessage> ARCHIVE_ROW_MAPPER = (rs, rowNum) ->
      PgmqMessage.builder()
          .msgId(rs.getLong("msg_id"))
          .readCount(rs.getInt("read_ct"))
          .enqueuedAt(rs.getObject("enqueued_at", OffsetDateTime.class))
          .deletedAt(rs.getObject("archived_at", OffsetDateTime.class))
          .message(rs.getString("message"))
          .build();

  private static final RowMapper<QueueMetrics> METRICS_ROW_MAPPER = (rs, rowNum) ->
      QueueMetrics.builder()
          .queueName(rs.getString("queue_name"))
          .queueLength(rs.getLong("queue_length"))
          .newestMsgAgeSec(rs.getObject("newest_msg_age_sec", Integer.class))
          .oldestMsgAgeSec(rs.getObject("oldest_msg_age_sec", Integer.class))
          .totalMessages(rs.getLong("total_messages"))
          .scrapeTime(rs.getObject("scrape_time", OffsetDateTime.class))
          .build();

  private void validateQueueName(String queueName) {
    if (queueName == null || !QUEUE_NAME_PATTERN.matcher(queueName).matches()) {
      throw new IllegalArgumentException(
          "Invalid queue name: '" + queueName
              + "'. Must start with a letter and contain only letters, numbers, and underscores (max 63 chars)."
      );
    }
  }

  // ── Extension ────────────────────────────────────────────────────────────

  public void enablePGMQExtension() {
    try {
      jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgmq CASCADE");
    } catch (DataAccessException e) {
      throw new IllegalStateException("Failed to enable 'pgmq' extension", e);
    }
  }

  // ── Queue Management ─────────────────────────────────────────────────────

  public void createQueue(String queueName) {
    validateQueueName(queueName);
    jdbcTemplate.queryForObject("SELECT pgmq.create(?)", String.class, queueName);
  }

  public void dropQueue(String queueName) {
    validateQueueName(queueName);
    jdbcTemplate.queryForObject("SELECT pgmq.drop_queue(?)", Boolean.class, queueName);
  }

  // ── Send ─────────────────────────────────────────────────────────────────

  public Long send(String queueName, String message) {
    validateQueueName(queueName);
    return jdbcTemplate.queryForObject(
        "SELECT pgmq.send(?, ?::jsonb)",
        Long.class, queueName, message
    );
  }

  public Long sendWithDelay(String queueName, String message, int delaySeconds) {
    validateQueueName(queueName);
    return jdbcTemplate.queryForObject(
        "SELECT pgmq.send(?, ?::jsonb, ?)",
        Long.class, queueName, message, delaySeconds
    );
  }

  public List<Long> sendBatch(String queueName, List<String> messages) {
    return sendBatch(queueName, messages, 0);
  }

  public List<Long> sendBatch(String queueName, List<String> messages, int delaySeconds) {
    validateQueueName(queueName);
    return jdbcTemplate.execute(
        (PreparedStatementCreator) conn -> {
          var ps = conn.prepareStatement("SELECT * FROM pgmq.send_batch(?, ?::jsonb[], ?)");
          ps.setString(1, queueName);
          Array arr = conn.createArrayOf("text", messages.toArray(String[]::new));
          ps.setArray(2, arr);
          ps.setInt(3, delaySeconds);
          return ps;
        },
        ps -> {
          var rs = ps.executeQuery();
          List<Long> ids = new ArrayList<>();
          while (rs.next()) {
            ids.add(rs.getLong(1));
          }
          return ids;
        }
    );
  }

  // ── Read ─────────────────────────────────────────────────────────────────

  public Optional<PgmqMessage> read(String queueName, int visibilityTimeoutSeconds) {
    validateQueueName(queueName);
    List<PgmqMessage> messages = jdbcTemplate.query(
        "SELECT * FROM pgmq.read(?, ?, 1)",
        MESSAGE_ROW_MAPPER, queueName, visibilityTimeoutSeconds
    );
    log.info("{}", messages);
    return messages.isEmpty() ? Optional.empty() : Optional.of(messages.getFirst());
  }

  public List<PgmqMessage> readBatch(String queueName, int visibilityTimeoutSeconds, int qty) {
    validateQueueName(queueName);
    return jdbcTemplate.query(
        "SELECT * FROM pgmq.read(?, ?, ?)",
        MESSAGE_ROW_MAPPER, queueName, visibilityTimeoutSeconds, qty
    );
  }

  // ── Pop ──────────────────────────────────────────────────────────────────

  public Optional<PgmqMessage> pop(String queueName) {
    validateQueueName(queueName);
    List<PgmqMessage> messages = jdbcTemplate.query(
        "SELECT * FROM pgmq.pop(?)",
        MESSAGE_ROW_MAPPER, queueName
    );
    return messages.isEmpty() ? Optional.empty() : Optional.of(messages.getFirst());
  }

  // ── Delete ───────────────────────────────────────────────────────────────

  public boolean delete(String queueName, long msgId) {
    validateQueueName(queueName);
    Boolean result = jdbcTemplate.queryForObject(
        "SELECT pgmq.delete(?, ?)",
        Boolean.class, queueName, msgId
    );
    return Boolean.TRUE.equals(result);
  }

  public List<Long> deleteBatch(String queueName, List<Long> msgIds) {
    validateQueueName(queueName);
    return jdbcTemplate.execute(
        (PreparedStatementCreator) conn -> {
          var ps = conn.prepareStatement("SELECT * FROM pgmq.delete(?, ?)");
          ps.setString(1, queueName);
          Array arr = conn.createArrayOf("bigint", msgIds.toArray(Long[]::new));
          ps.setArray(2, arr);
          return ps;
        },
        ps -> {
          var rs = ps.executeQuery();
          List<Long> ids = new ArrayList<>();
          while (rs.next()) {
            ids.add(rs.getLong(1));
          }
          return ids;
        }
    );
  }

  // ── Archive ──────────────────────────────────────────────────────────────

  public boolean archive(String queueName, long msgId) {
    validateQueueName(queueName);
    Boolean result = jdbcTemplate.queryForObject(
        "SELECT pgmq.archive(?, ?)",
        Boolean.class, queueName, msgId
    );
    return Boolean.TRUE.equals(result);
  }

  public List<Long> archiveBatch(String queueName, List<Long> msgIds) {
    validateQueueName(queueName);
    return jdbcTemplate.execute(
        (PreparedStatementCreator) conn -> {
          var ps = conn.prepareStatement("SELECT * FROM pgmq.archive(?, ?)");
          ps.setString(1, queueName);
          Array arr = conn.createArrayOf("bigint", msgIds.toArray(Long[]::new));
          ps.setArray(2, arr);
          return ps;
        },
        ps -> {
          var rs = ps.executeQuery();
          List<Long> ids = new ArrayList<>();
          while (rs.next()) {
            ids.add(rs.getLong(1));
          }
          return ids;
        }
    );
  }

  // ── Peek Archive ─────────────────────────────────────────────────────────

  public List<PgmqMessage> peekArchive(String queueName, int limit) {
    validateQueueName(queueName);
    try {
      return jdbcTemplate.query(
          "SELECT msg_id, read_ct, enqueued_at, archived_at, message FROM pgmq.a_" + queueName
              + " ORDER BY archived_at DESC LIMIT ?",
          ARCHIVE_ROW_MAPPER, limit
      );
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  // ── Metrics ──────────────────────────────────────────────────────────────

  public Optional<QueueMetrics> getQueueMetrics(String queueName) {
    validateQueueName(queueName);
    List<QueueMetrics> metrics = jdbcTemplate.query(
        "SELECT * FROM pgmq.metrics(?)",
        METRICS_ROW_MAPPER, queueName
    );
    return metrics.isEmpty() ? Optional.empty() : Optional.of(metrics.getFirst());
  }

  public List<QueueMetrics> getAllQueueMetrics() {
    return jdbcTemplate.query("SELECT * FROM pgmq.metrics_all()", METRICS_ROW_MAPPER);
  }

  // ── Peek (non-destructive read for monitoring) ────────────────────────────

  public List<PgmqMessage> peek(String queueName, int limit) {
    validateQueueName(queueName);
    try {
      // Direct table query — safe because queueName passes strict regex validation
      return jdbcTemplate.query(
          "SELECT msg_id, read_ct, enqueued_at, vt, message FROM pgmq.q_" + queueName
              + " ORDER BY msg_id DESC LIMIT ?",
          MESSAGE_ROW_MAPPER, limit
      );
    } catch (DataAccessException e) {
      return List.of();
    }
  }
}
