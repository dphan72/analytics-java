package com.segment.analytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.segment.analytics.internal.AnalyticsClient;
import com.segment.analytics.internal.gson.AutoValueAdapterFactory;
import com.segment.analytics.internal.gson.LowerCaseEnumTypeAdapterFactory;
import com.segment.analytics.internal.http.SegmentService;
import com.segment.analytics.messages.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.client.Client;
import retrofit.converter.GsonConverter;

import static com.segment.analytics.internal.Utils.basicCredentials;
import static com.segment.analytics.internal.Utils.isNullOrEmpty;

/**
 * The entry point into the Segment for Java library.
 * <p/>
 * The idea is simple: one pipeline for all your data. Segment is the single hub to collect,
 * translate and route your data with the flip of a switch.
 * <p/>
 * Analytics for Java will automatically batch events and upload it periodically to Segment's
 * servers for you. You only need to instrument Segment once, then flip a switch to install
 * new tools.
 * <p/>
 * This class is the main entry point into the client API. Use {@link Builder} to construct your
 * own instances.
 *
 * @see <a href="https://Segment/">Segment</a>
 */
public class Analytics {
  private final AnalyticsClient client;
  private final List<MessageInterceptor> messageInterceptors;

  Analytics(AnalyticsClient client, List<MessageInterceptor> messageInterceptors) {
    this.client = client;
    this.messageInterceptors = messageInterceptors;
  }

  /** Enqueue the given message to be uploaded to Segment's servers. */
  public void enqueue(Message message) {
    for (int i = 0, size = messageInterceptors.size(); i < size; i++) {
      message = messageInterceptors.get(i).intercept(message);
      if (message == null) {
        // todo: log
        return;
      }
    }
    client.enqueue(message);
  }

  /** Flush events in the message queue. */
  public void flush() {
    client.flush();
  }

  /** Stops this instance from processing further requests. */
  public void shutdown() {
    client.shutdown();
  }

  /** Fluent API for creating {@link Analytics} instances. */
  public static class Builder {
    private final String writeKey;
    private Client client;
    private Log log;
    private List<MessageInterceptor> messageInterceptors;
    private ExecutorService networkExecutor;
    private ThreadFactory threadFactory;
    private int flushQueueSize;
    private long flushIntervalInMillis;

    /**
     * Start building a new {@link Analytics} instance.
     *
     * @param writeKey Your project write key available on the Segment dashboard.
     */
    public Builder(String writeKey) {
      if (isNullOrEmpty(writeKey)) {
        throw new NullPointerException("category cannot be null or empty.");
      }
      this.writeKey = writeKey;
    }

    /** Set a custom networking client. */
    public Builder client(Client client) {
      if (client == null) {
        throw new NullPointerException("Null client");
      }
      this.client = client;
      return this;
    }

    /** Configure debug logging mechanism. By default, nothing is logged. */
    public Builder log(Log log) {
      if (log == null) {
        throw new NullPointerException("Null log");
      }
      this.log = log;
      return this;
    }

    /** Add a message interceptor for transforming every message. */
    public Builder messageInterceptor(MessageInterceptor interceptor) {
      if (interceptor == null) {
        throw new IllegalArgumentException("Null interceptor");
      }
      if (messageInterceptors == null) {
        messageInterceptors = new ArrayList<>();
      }
      if (messageInterceptors.contains(interceptor)) {
        throw new IllegalStateException("MessageInterceptor is already registered.");
      }
      messageInterceptors.add(interceptor);
      return this;
    }

    /**
     * Set the queueSize at which flushes should be triggered.
     * <p></p>
     * Note: Although functionally stable, this is a beta API and the name might be changed in a
     * later release.
     */
    public Builder flushQueueSize(int flushQueueSize) {
      if (flushQueueSize < 1) {
        throw new IllegalArgumentException("flushQueueSize must not be less than 1.");
      }
      this.flushQueueSize = flushQueueSize;
      return this;
    }

    /**
     * Set the interval at which the queue should be flushed.
     * <p></p>
     * Note: Although functionally stable, this is a beta API and the name might be changed in a
     * later release.
     */
    public Builder flushInterval(int flushInterval, TimeUnit unit) {
      long flushIntervalInMillis = unit.toMillis(flushInterval);
      if (flushIntervalInMillis < 1000) {
        throw new IllegalArgumentException("flushIntervalInMillis must not be less than 1 second.");
      }
      this.flushIntervalInMillis = flushIntervalInMillis;
      return this;
    }

    /** Set the executor on which all HTTP requests will be made. */
    public Builder networkExecutor(ExecutorService networkExecutor) {
      if (networkExecutor == null) {
        throw new NullPointerException("Null networkExecutor");
      }
      this.networkExecutor = networkExecutor;
      return this;
    }

    /** Set the ThreadFactory used to create threads. */
    public Builder threadFactory(ThreadFactory threadFactory) {
      if (threadFactory == null) {
        throw new NullPointerException("Null threadFactory");
      }
      this.threadFactory = threadFactory;
      return this;
    }

    /** Create a {@link Analytics} client. */
    public Analytics build() {
      Gson gson = new GsonBuilder() //
          .registerTypeAdapterFactory(new AutoValueAdapterFactory())
          .registerTypeAdapterFactory(new LowerCaseEnumTypeAdapterFactory<>(Message.Type.class))
          .create();

      if (client == null) {
        client = Platform.get().defaultClient();
      }
      if (log == null) {
        log = Log.NONE;
      }
      if (flushIntervalInMillis == 0) {
        flushIntervalInMillis = Platform.get().defaultFlushIntervalInMillis();
      }
      if (flushQueueSize == 0) {
        flushQueueSize = Platform.get().defaultFlushQueueSize();
      }
      if (messageInterceptors == null) {
        messageInterceptors = Collections.emptyList();
      } else {
        messageInterceptors = Collections.unmodifiableList(messageInterceptors);
      }
      if (networkExecutor == null) {
        networkExecutor = Platform.get().defaultNetworkExecutor();
      }
      if (threadFactory == null) {
        threadFactory = Platform.get().defaultThreadFactory();
      }

      RestAdapter restAdapter = new RestAdapter.Builder().setConverter(new GsonConverter(gson))
          .setEndpoint("https://api.segment.io")
          .setClient(client)
          .setRequestInterceptor(new RequestInterceptor() {
            @Override public void intercept(RequestFacade request) {
              request.addHeader("Authorization", basicCredentials(writeKey, ""));
            }
          })
          .setLogLevel(RestAdapter.LogLevel.FULL)
          .setLog(new RestAdapter.Log() {
            @Override public void log(String message) {
              log.v(message);
            }
          })
          .build();

      SegmentService segmentService = restAdapter.create(SegmentService.class);

      AnalyticsClient analyticsClient =
          new AnalyticsClient(new LinkedBlockingDeque<Message>(), segmentService, flushQueueSize,
              flushIntervalInMillis, log, threadFactory, networkExecutor);

      return new Analytics(analyticsClient, messageInterceptors);
    }
  }
}