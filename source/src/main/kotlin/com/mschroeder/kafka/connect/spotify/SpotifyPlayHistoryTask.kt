package com.mschroeder.kafka.connect.spotify

import com.mschroeder.kafka.common.schema.PlayHistory
import com.mschroeder.kafka.common.schema.PlayHistoryKey
import com.mschroeder.kafka.common.schema.StructFactory
import com.mschroeder.kafka.common.schema.toStruct
import com.mschroeder.kafka.common.spotify.SpotifyClient
import com.mschroeder.kafka.connect.spotify.config.Config
import com.mschroeder.kafka.connect.spotify.config.SpotifySourceConfig
import com.wrapper.spotify.model_objects.specification.PagingCursorbased
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.apache.kafka.connect.source.SourceTaskContext
import org.slf4j.LoggerFactory
import java.util.*
import com.wrapper.spotify.model_objects.specification.PlayHistory as PlayHistoryModel

/**
 * This Source Task polls against Spotify's recently played API for a user.
 */
class SpotifyPlayHistoryTask : SourceTask() {
    private val log = LoggerFactory.getLogger(SpotifyPlayHistoryTask::class.java)

    // lateinit due to not having config until start()
    private lateinit var taskConfig: SpotifySourceConfig
    private lateinit var client: SpotifyClient
    private lateinit var topic: String
    private lateinit var pollingInterval: Number
    private lateinit var partition:  MutableMap<String, String>
    private lateinit var username: String
    private lateinit var offset: Number

    companion object {
        private const val PARTITION_ID: String = "username"
        private const val OFFSET_ID: String = "played_at"
    }

    override fun start(config: MutableMap<String, String>?) {
        log.info("Starting SpotifyPlayHistoryTask v${version()}")

        configureTask(config)
    }

    override fun stop() {
        log.info("Stopping SpotifyPlayHistoryTask")
    }

    override fun version(): String = Config.VERSION

    override fun poll(): MutableList<SourceRecord> {
        log.info("** polling in $pollingInterval seconds **")

        Thread.sleep(1000 * pollingInterval.toLong())

        val playHistoryPage = client.getRecentlyPlayedTracks(offset)

        // keep the offset up to date locally (the latest offset sent to kafka will be persisted as well)
        updateLocalOffset(playHistoryPage)

        val records = playHistoryPage.items
                .toMutableList()
                // reverse list so that items are ordered oldest -> newest
                .asReversed()
                .map {
                    SourceRecord(
                            partition,
                            mutableMapOf(OFFSET_ID to it.playedAt.time),
                            topic,
                            // message key
                            PlayHistoryKey.SCHEMA,
                            StructFactory.createPlayHistoryKey(username, it.track.id),
                            // message value
                            PlayHistory.SCHEMA,
                            it.toStruct()
                    )
                }

        return records.toMutableList()
    }

    /**
     * The current offset is determined by the most recent message produced.
     * However, we also want to keep track of the current offset locally
     * so that we don't have to load it out of Kafka on each poll()
     *
     * This method will parse the `after` cursor out of the spotify paging object.
     * Cursors are not always provided (if no results are returned) and there aren't
     * always both cursors (before && after).
     *
     * Snippet of response with cursors:
     * {
     *   "cursors": {
     *     "after": "1530136483329",
     *     "before": "1530136483329"
     *   }
     * }
     */
    private fun updateLocalOffset(page: PagingCursorbased<PlayHistoryModel>) {
        if (page.cursors != null) {
            page.cursors
                .forEach { cursor ->
                    if (cursor != null && cursor.after != null) {
                        offset = cursor.after.toLong()
                    }
                }
        }
    }

    /**************
     * Task Setup *
     **************/

    private fun configureTask(config: MutableMap<String, String>?) {
        log.info("Configuring SpotifyPlayHistoryTask and SpotifyClient")

        // initialize task config
        taskConfig = SpotifySourceConfig(Config.spotify, config)
        topic = taskConfig.getString(Config.SPOTIFY_KAFKA_TOPIC_CONF)
        pollingInterval = taskConfig.getInt(Config.SPOTIFY_POLLING_INTERVAL_CONF)

        client = createSpotifyClient()

        username = taskConfig.getString(Config.SPOTIFY_USERNAME_CONF)
        partition = mutableMapOf(PARTITION_ID to username)
        offset = loadOffset(this.context, timestamp(6))

        log.info("Task Partition: User $username")
        log.info("Task Offset: $offset")
        log.info("SpotifyPlayHistoryTask is configured and ready to rock")
    }

    /**
     * Configures and creates a Spotify client
     */
    private fun createSpotifyClient(): SpotifyClient {
        val oauthToken = taskConfig.getPassword(Config.SPOTIFY_OAUTH_ACCESS_TOKEN_CONF).value()
        val clientId = taskConfig.getPassword(Config.SPOTIFY_OAUTH_CLIENT_ID_CONF).value()
        val clientSecret = taskConfig.getPassword(Config.SPOTIFY_OAUTH_CLIENT_SECRET_CONF).value()

        return SpotifyClient(oauthToken, clientId, clientSecret)
    }

    /**
     * Loads the starting offset for the SpotifyPlayHistoryTask.
     *
     * If an offset exists (i.e. the task has been previously run) then the task will pick up
     * from that offset. If it doesn't exist (i.e. this is the task's initial run) then it will
     * start the task from 6 months prior.
     */
    private fun loadOffset(context: SourceTaskContext, default: Long): Number {
        val savedOffset = context.offsetStorageReader().offset(partition)

        return when {
            savedOffset != null -> savedOffset[OFFSET_ID] as Long
            else -> default
        }
    }

    /**
     * Helper to generate a timestamp however many months prior you specify
     */
    private fun timestamp(monthsPrior: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, monthsPrior * -1)
        return cal.time.time
    }
}