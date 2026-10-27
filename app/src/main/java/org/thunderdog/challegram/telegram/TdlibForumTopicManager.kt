package org.thunderdog.challegram.telegram

import me.vkryl.core.reference.ReferenceMap
import moe.kirao.mgx.MoexConfig
import org.drinkless.tdlib.TdApi.*

class TdlibForumTopicManager(
  tdlib: Tdlib
) : TdlibDataManager<TdlibForumTopicManager.Key, ForumTopic, TdlibForumTopicManager.Entry>(tdlib) {
  companion object {
    private const val UNREAD_REFRESH_DELAY_SECONDS = 1.0
    private const val UNREAD_REFRESH_PAGE_SIZE = 100
    private const val UNREAD_REFRESH_MAX_PAGES = 10
  }

  data class Key(
    @JvmField val chatId: Long,
    @JvmField val forumTopicId: Int
  )
  class Entry : AbstractEntry<Key, ForumTopic> {
    @JvmField val forumTopicId: Int

    constructor(key: Key, value: ForumTopic?, error: Error?) : super(key, value, error) {
      this.forumTopicId = key.forumTopicId
    }
  }

  interface Watcher : TdlibDataManager.Watcher<Key, ForumTopic, Entry> {
    fun onForumTopicLoaded (context: TdlibForumTopicManager, entry: Entry)

    override fun onEntryLoaded(context: TdlibDataManager<Key, ForumTopic, Entry>, entry: Entry) {
      onForumTopicLoaded(context as TdlibForumTopicManager, entry)
    }
  }

  override fun newEntry(key: Key, value: ForumTopic?, error: Error?): Entry =
    Entry(key, value, error)

  override fun requestData(contextId: Int, keysToRequest: Collection<Key>) {
    for (key in keysToRequest) {
      tdlib.send(GetForumTopic(key.chatId, key.forumTopicId)) { forumTopic, error ->
        if (!isCancelled(contextId)) {
          if (error != null) {
            processError(contextId, key, error)
          } else {
            processData(contextId, key, forumTopic)
          }
        }
      }
    }
  }

  interface Observer {
    fun onTopicFound(key: Key, topic: ForumTopic, inPlace: Boolean)
    fun onTopicUpdated(key: Key, update: UpdateForumTopic)
    fun onTopicInfoUpdated(key: Key, topicInfo: ForumTopicInfo)
  }

  @TdlibThread
  fun updateForumTopic(update: UpdateForumTopic) {
    val key = Key(
      update.chatId,
      update.forumTopicId
    )
    var readInboxAdvanced = false
    val isUpdated = replaceExisting(key) { prevValue, _ ->
      if (prevValue != null) {
        readInboxAdvanced = update.lastReadInboxMessageId > prevValue.lastReadInboxMessageId
        ForumTopic(
          prevValue.info,
          prevValue.lastMessage,
          prevValue.order,
          update.isPinned,
          prevValue.unreadCount,
          update.lastReadInboxMessageId,
          update.lastReadOutboxMessageId,
          update.unreadMentionCount,
          update.unreadReactionCount,
          update.unreadPollVoteCount,
          update.notificationSettings,
          update.draftMessage
        );
      } else {
        null
      }
    }
    if (isUpdated) {
      observers.iterate(key) { observer ->
        observer.onTopicUpdated(key, update)
      }
    }
    onTopicReadInboxUpdated(update.chatId, update.forumTopicId, update.lastReadInboxMessageId, readInboxAdvanced)
  }

  @TdlibThread
  fun updateForumTopicInfo(update: UpdateForumTopicInfo) {
    val key = Key(
      update.info.chatId,
      update.info.forumTopicId
    )
    val isUpdated = replaceExisting(key) { prevValue, _ ->
      if (prevValue != null) {
        ForumTopic(
          update.info,
          prevValue.lastMessage,
          prevValue.order,
          prevValue.isPinned,
          prevValue.unreadCount,
          prevValue.lastReadInboxMessageId,
          prevValue.lastReadOutboxMessageId,
          prevValue.unreadMentionCount,
          prevValue.unreadReactionCount,
          prevValue.unreadPollVoteCount,
          prevValue.notificationSettings,
          prevValue.draftMessage
        )
      } else {
        null
      }
    }
    if (isUpdated) {
      observers.iterate(key) { observer ->
        observer.onTopicInfoUpdated(key, update.info)
      }
    }
  }

  fun cacheTopic (topic: ForumTopic) {
    put(Key(topic.info.chatId, topic.info.forumTopicId), topic)
  }

  fun findTopic (chatId: Long, forumTopicId: Int): ForumTopic? =
    find(Key(chatId, forumTopicId))?.value

  // Forum unread counters

  // For forums chat.unreadCount is a server value that isn't updated when messages are read in topics,
  // so for forums viewed as topics the counter shown in the chat list is calculated from topics.
  // Additionally, once messages up to some id are read in all topics, the chat history
  // is marked as read up to that message, so other clients see the chat as read as well.

  private class TopicUnreadState(
    var unreadCount: Int,
    var lastReadInboxMessageId: Long,
    var lastMessageId: Long
  )

  private class ForumUnreadState {
    val topics = HashMap<Int, TopicUnreadState>()
  }

  // All guarded by forumUnreadStates
  private val forumUnreadStates = HashMap<Long, ForumUnreadState>()
  private val scheduledRefreshes = HashSet<Long>()
  private val inFlightRefreshes = HashSet<Long>()
  private val outdatedRefreshes = HashSet<Long>()
  private val failedRefreshes = HashSet<Long>()

  /**
   * @return Unread counter of the forum chat calculated from its topics, or -1 if it's not known yet
   */
  fun getForumUnreadCount(chatId: Long): Int {
    val countTopics = MoexConfig.forumUnreadCounter == MoexConfig.FORUM_UNREAD_COUNTER_TOPICS
    synchronized(forumUnreadStates) {
      if (failedRefreshes.contains(chatId)) {
        return -1
      }
      val state = forumUnreadStates[chatId]
      if (state != null) {
        return if (countTopics) {
          state.topics.values.count { it.unreadCount > 0 }
        } else {
          state.topics.values.sumOf { it.unreadCount }
        }
      }
    }
    if (tdlib.isForum(chatId)) {
      scheduleUnreadRefresh(chatId)
    }
    return -1
  }

  fun notifyForumUnreadCountersChanged() {
    tdlib.runOnTdlibThread({
      val chatIds = synchronized(forumUnreadStates) {
        forumUnreadStates.keys.toLongArray()
      }
      for (chatId in chatIds) {
        tdlib.notifyChatReadInboxChanged(chatId)
      }
    }, 0.0, false)
  }

  @TdlibThread
  fun onNewMessage(message: Message) {
    val topicId = message.topicId as? MessageTopicForum ?: return
    if (message.isOutgoing) {
      return
    }
    var changed = false
    synchronized(forumUnreadStates) {
      if (inFlightRefreshes.contains(message.chatId)) {
        outdatedRefreshes.add(message.chatId)
      }
      val state = forumUnreadStates[message.chatId] ?: return
      val topicState = state.topics.getOrPut(topicId.forumTopicId) {
        TopicUnreadState(0, 0L, 0L)
      }
      if (message.id > topicState.lastMessageId) {
        topicState.lastMessageId = message.id
        if (message.id > topicState.lastReadInboxMessageId) {
          topicState.unreadCount++
          changed = true
        }
      }
    }
    if (changed) {
      tdlib.notifyChatReadInboxChanged(message.chatId)
    }
  }

  @TdlibThread
  fun onChatReadInbox(chatId: Long, unreadCount: Int) {
    if (unreadCount == 0) {
      // Everything is read, counters will be loaded again once new messages arrive
      synchronized(forumUnreadStates) {
        forumUnreadStates.remove(chatId)
      }
    }
  }

  private fun onTopicReadInboxUpdated(chatId: Long, forumTopicId: Int, lastReadInboxMessageId: Long, cachedTopicReadAdvanced: Boolean) {
    var needRefresh = cachedTopicReadAdvanced
    var changedState: ForumUnreadState? = null
    synchronized(forumUnreadStates) {
      val state = forumUnreadStates[chatId]
      val topicState = state?.topics?.get(forumTopicId)
      if (topicState != null && lastReadInboxMessageId > topicState.lastReadInboxMessageId) {
        topicState.lastReadInboxMessageId = lastReadInboxMessageId
        if (lastReadInboxMessageId >= topicState.lastMessageId) {
          // Topic is fully read, no need to request anything
          topicState.unreadCount = 0
          changedState = state
          needRefresh = false
        } else {
          // Partially read, exact unread count is known only to TDLib
          needRefresh = true
        }
      }
    }
    changedState?.let {
      tdlib.notifyChatReadInboxChanged(chatId)
      syncChatReadState(chatId, it)
    }
    if (needRefresh) {
      scheduleUnreadRefresh(chatId)
    }
  }

  private fun scheduleUnreadRefresh(chatId: Long) {
    val chat = tdlib.chat(chatId)
    if (chat == null || chat.unreadCount == 0) {
      return
    }
    synchronized(forumUnreadStates) {
      if (failedRefreshes.contains(chatId)) {
        return
      }
      if (inFlightRefreshes.contains(chatId)) {
        outdatedRefreshes.add(chatId)
        return
      }
      if (!scheduledRefreshes.add(chatId)) {
        return
      }
    }
    tdlib.runOnTdlibThread({
      startUnreadRefresh(chatId)
    }, UNREAD_REFRESH_DELAY_SECONDS, false)
  }

  private fun startUnreadRefresh(chatId: Long) {
    synchronized(forumUnreadStates) {
      scheduledRefreshes.remove(chatId)
      inFlightRefreshes.add(chatId)
    }
    val chat = tdlib.chat(chatId)
    if (chat == null || chat.unreadCount == 0 || !tdlib.isForum(chatId)) {
      finishUnreadRefresh(chatId, null, false)
    } else {
      loadAllTopics(chatId, ArrayList(), 0, 0L, 0, 0)
    }
  }

  private fun loadAllTopics(chatId: Long, topics: ArrayList<ForumTopic>, offsetDate: Int, offsetMessageId: Long, offsetForumTopicId: Int, page: Int) {
    tdlib.send(GetForumTopics(chatId, "", offsetDate, offsetMessageId, offsetForumTopicId, UNREAD_REFRESH_PAGE_SIZE)) { result, error ->
      if (error != null) {
        finishUnreadRefresh(chatId, null, true)
        return@send
      }
      topics.addAll(result.topics)
      val endReached = result.topics.isEmpty() || (result.nextOffsetDate == 0 && result.nextOffsetMessageId == 0L && result.nextOffsetForumTopicId == 0)
      if (endReached) {
        finishUnreadRefresh(chatId, topics, false)
      } else if (page + 1 < UNREAD_REFRESH_MAX_PAGES) {
        loadAllTopics(chatId, topics, result.nextOffsetDate, result.nextOffsetMessageId, result.nextOffsetForumTopicId, page + 1)
      } else {
        // Too many topics, fallback to the counter provided by TDLib
        finishUnreadRefresh(chatId, null, true)
      }
    }
  }

  private fun finishUnreadRefresh(chatId: Long, topics: List<ForumTopic>?, failed: Boolean) {
    var state: ForumUnreadState? = null
    if (topics != null) {
      state = ForumUnreadState()
      for (topic in topics) {
        val cachedTopic = findTopic(chatId, topic.info.forumTopicId)
        // Local read state may be newer than the received one
        val lastReadInboxMessageId = maxOf(topic.lastReadInboxMessageId, cachedTopic?.lastReadInboxMessageId ?: 0L)
        val lastMessageId = topic.lastMessage?.id ?: 0L
        val unreadCount = if (lastReadInboxMessageId >= lastMessageId) 0 else topic.unreadCount
        state.topics[topic.info.forumTopicId] = TopicUnreadState(unreadCount, lastReadInboxMessageId, lastMessageId)
      }
    }
    val refreshAgain: Boolean
    synchronized(forumUnreadStates) {
      inFlightRefreshes.remove(chatId)
      refreshAgain = outdatedRefreshes.remove(chatId) && !failed
      if (failed) {
        failedRefreshes.add(chatId)
      }
      if (state != null) {
        forumUnreadStates[chatId] = state
      }
    }
    if (state != null) {
      tdlib.notifyChatReadInboxChanged(chatId)
      syncChatReadState(chatId, state)
    }
    if (refreshAgain) {
      scheduleUnreadRefresh(chatId)
    }
  }

  private fun syncChatReadState(chatId: Long, state: ForumUnreadState) {
    var minUnreadTopicReadId = Long.MAX_VALUE
    var maxReadTopicMessageId = 0L
    synchronized(forumUnreadStates) {
      for (topicState in state.topics.values) {
        if (topicState.unreadCount > 0) {
          minUnreadTopicReadId = minOf(minUnreadTopicReadId, topicState.lastReadInboxMessageId)
        } else {
          maxReadTopicMessageId = maxOf(maxReadTopicMessageId, topicState.lastMessageId)
        }
      }
    }
    // Messages up to this id are read in all topics
    val readUntilMessageId = if (minUnreadTopicReadId != Long.MAX_VALUE) minUnreadTopicReadId else maxReadTopicMessageId
    val chat = tdlib.chat(chatId) ?: return
    if (chat.unreadCount == 0 || readUntilMessageId == 0L || readUntilMessageId <= chat.lastReadInboxMessageId) {
      return
    }
    tdlib.send(ViewMessages(chatId, longArrayOf(readUntilMessageId), MessageSourceChatHistory(), true), tdlib.typedOkHandler())
  }

  private val observers = ReferenceMap<Key, Observer>(true)

  fun findAndObserve(key: Key, observer: Observer): Entry? {
    observers.add(key, observer)
    return findOrRequest(key) { entry, inPlace ->
      if (!entry.isNotFound) {
        observer.onTopicFound(key, entry.value!!, inPlace)
      }
    }
  }

  fun stopObserving(key: Key, observer: Observer) {
    observers.remove(key, observer)
  }
}