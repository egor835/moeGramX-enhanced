/*
 * This file is a part of moeGramX
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.AvatarPlaceholder;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.text.Letters;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.BetterChatView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

/**
 * List of topics of a forum supergroup.
 */
public class ForumTopicsController extends RecyclerViewController<ForumTopicsController.Args> implements View.OnClickListener, MessageListener {
  private static final int PAGE_SIZE = 30;

  public static class Args {
    public final TdApi.ChatList chatList;
    public final TdApi.Chat chat;

    public Args (@Nullable TdApi.ChatList chatList, @NonNull TdApi.Chat chat) {
      this.chatList = chatList;
      this.chat = chat;
    }
  }

  public ForumTopicsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_forumTopics;
  }

  @Override
  public CharSequence getName () {
    return tdlib.chatTitle(getArgumentsStrict().chat);
  }

  private long chatId () {
    return getArgumentsStrict().chat.id;
  }

  // Data

  private final List<TdApi.ForumTopic> topics = new ArrayList<>();
  private boolean isLoading, endReached, isLoaded;
  private int nextOffsetDate;
  private long nextOffsetMessageId;
  private int nextOffsetForumTopicId;
  private int loadGeneration;

  private void loadMore () {
    if (isLoading || endReached) {
      return;
    }
    isLoading = true;
    final int generation = loadGeneration;
    final long chatId = chatId();
    tdlib.send(new TdApi.GetForumTopics(chatId, "", nextOffsetDate, nextOffsetMessageId, nextOffsetForumTopicId, PAGE_SIZE), (result, error) -> runOnUiThreadOptional(() -> {
      if (generation != loadGeneration) {
        return;
      }
      isLoading = false;
      isLoaded = true;
      if (error != null) {
        endReached = true;
        UI.showError(error);
        buildCells();
        return;
      }
      for (TdApi.ForumTopic topic : result.topics) {
        tdlib.topics().cacheTopic(topic);
        replaceTopic(topic);
      }
      endReached = result.topics.length == 0 || (result.nextOffsetDate == 0 && result.nextOffsetMessageId == 0 && result.nextOffsetForumTopicId == 0);
      nextOffsetDate = result.nextOffsetDate;
      nextOffsetMessageId = result.nextOffsetMessageId;
      nextOffsetForumTopicId = result.nextOffsetForumTopicId;
      sortTopics();
      buildCells();
    }));
  }

  private void reload () {
    loadGeneration++;
    isLoading = false;
    endReached = false;
    nextOffsetDate = 0;
    nextOffsetMessageId = 0;
    nextOffsetForumTopicId = 0;
    // Existing topics are kept until the fresh ones arrive, so the list doesn't blink
    loadMore();
  }

  private int indexOfTopic (int forumTopicId) {
    for (int i = 0; i < topics.size(); i++) {
      if (topics.get(i).info.forumTopicId == forumTopicId) {
        return i;
      }
    }
    return -1;
  }

  private void replaceTopic (TdApi.ForumTopic topic) {
    int index = indexOfTopic(topic.info.forumTopicId);
    if (index != -1) {
      topics.set(index, topic);
    } else {
      topics.add(topic);
    }
  }

  private void sortTopics () {
    Collections.sort(topics, (a, b) -> {
      if (a.order != b.order) {
        return Long.compare(b.order, a.order);
      }
      return Integer.compare(b.info.forumTopicId, a.info.forumTopicId);
    });
  }

  private void refreshTopic (int forumTopicId) {
    final long chatId = chatId();
    tdlib.send(new TdApi.GetForumTopic(chatId, forumTopicId), (topic, error) -> {
      if (topic != null) {
        tdlib.topics().cacheTopic(topic);
        runOnUiThreadOptional(() -> {
          replaceTopic(topic);
          sortTopics();
          buildCells();
        });
      }
    });
  }

  // UI

  private SettingsAdapter adapter;

  private void buildCells () {
    if (adapter == null) {
      return;
    }
    List<ListItem> items = new ArrayList<>(topics.size() + 2);
    if (topics.isEmpty()) {
      if (isLoaded && endReached) {
        items.add(new ListItem(ListItem.TYPE_EMPTY, 0, 0, R.string.ForumTopicsEmpty));
      } else {
        items.add(new ListItem(ListItem.TYPE_PROGRESS));
      }
    } else {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      for (TdApi.ForumTopic topic : topics) {
        items.add(new ListItem(ListItem.TYPE_CHAT_BETTER, R.id.btn_forumTopic).setLongId(topic.info.forumTopicId).setData(topic));
      }
      if (!endReached) {
        items.add(new ListItem(ListItem.TYPE_PROGRESS));
      }
    }
    adapter.setItems(items, false);
  }

  private static @TdlibAccentColor.BuiltInId int toAccentColorId (int iconColor) {
    switch (iconColor & 0xFFFFFF) {
      case 0x6FB9F0:
        return TdlibAccentColor.BuiltInId.BLUE;
      case 0xFFD67E:
        return TdlibAccentColor.BuiltInId.ORANGE;
      case 0xCB86DB:
        return TdlibAccentColor.BuiltInId.PURPLE_VIOLET;
      case 0x8EEE98:
        return TdlibAccentColor.BuiltInId.GREEN;
      case 0xFF93B2:
        return TdlibAccentColor.BuiltInId.PINK;
      case 0xFB6F5F:
        return TdlibAccentColor.BuiltInId.RED;
      default:
        return TdlibAccentColor.BuiltInId.CYAN;
    }
  }

  private boolean isMuted (TdApi.ForumTopic topic) {
    TdApi.ChatNotificationSettings settings = topic.notificationSettings;
    if (settings != null && !settings.useDefaultMuteFor) {
      return settings.muteFor > 0;
    }
    return !tdlib.chatNotificationsEnabled(chatId());
  }

  private void setTopicData (TdApi.ForumTopic topic, BetterChatView view) {
    TdApi.ForumTopicInfo info = topic.info;
    String title = info.name;
    if (info.isClosed) {
      title = "🔒 " + title;
    }
    view.setTitle(title);

    Letters letters = info.isGeneral ? new Letters("#") : TD.getLetters(info.name);
    TdlibAccentColor accentColor = new TdlibAccentColor(info.isGeneral ? TdlibAccentColor.BuiltInId.BLUE : toAccentColorId(info.icon != null ? info.icon.color : 0));
    view.setAvatar(null, new AvatarPlaceholder.Metadata(accentColor, letters));

    TdApi.Message lastMessage = topic.lastMessage;
    if (lastMessage != null) {
      ContentPreview preview = ContentPreview.getChatListPreview(tdlib, info.chatId, lastMessage, true);
      String text = preview.buildText(false);
      String sender = lastMessage.isOutgoing ? Lang.getString(R.string.FromYou) : tdlib.senderName(lastMessage, true, true);
      view.setSubtitle(StringUtils.isEmpty(sender) ? text : sender + ": " + text);
      view.setTime(Lang.timeOrDateShort(lastMessage.date, TimeUnit.SECONDS));
    } else {
      view.setSubtitle(Lang.getString(R.string.ForumTopicNoMessages));
      view.setTime(null);
    }
    view.setUnreadCount(topic.unreadCount, isMuted(topic), false);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setChatData (ListItem item, int position, BetterChatView chatView) {
        setTopicData((TdApi.ForumTopic) item.getData(), chatView);
      }
    };
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager != null && !isLoading && !endReached && manager.findLastVisibleItemPosition() + 5 >= adapter.getItemCount()) {
          loadMore();
        }
      }
    });
    buildCells();
    loadMore();
    tdlib.listeners().subscribeToMessageUpdates(chatId(), this);
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId(), this);
  }

  private boolean needRefreshOnFocus;

  @Override
  public void onFocus () {
    super.onFocus();
    if (needRefreshOnFocus) {
      needRefreshOnFocus = false;
      reload();
    }
  }

  @Override
  public void onBlur () {
    super.onBlur();
    needRefreshOnFocus = true;
  }

  // Updates

  private void onTopicMessage (@Nullable TdApi.Message message) {
    if (message == null || message.topicId == null || message.topicId.getConstructor() != TdApi.MessageTopicForum.CONSTRUCTOR) {
      return;
    }
    final int forumTopicId = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
    runOnUiThreadOptional(() -> refreshTopic(forumTopicId));
  }

  @Override
  public void onNewMessage (TdApi.Message message) {
    onTopicMessage(message);
  }

  @Override
  public void onMessageSendSucceeded (TdApi.Message message, long oldMessageId) {
    onTopicMessage(message);
  }

  // Header

  @Override
  protected int getMenuId () {
    return R.id.menu_forumTopics;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_forumTopics) {
      header.addButton(menu, R.id.menu_btn_viewAsMessages, R.drawable.baseline_chat_bubble_24, getHeaderIconColorId(), this, Screen.dp(49f));
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_viewAsMessages) {
      showOptions(null, new int[] {R.id.btn_viewAsMessages, R.id.btn_openChatProfile}, new String[] {
        Lang.getString(R.string.ForumViewAsMessages),
        Lang.getString(R.string.ForumOpenProfile)
      }, new int[] {OptionColor.NORMAL, OptionColor.NORMAL}, new int[] {R.drawable.baseline_chat_bubble_24, R.drawable.baseline_info_24}, (itemView, optionId) -> {
        if (optionId == R.id.btn_viewAsMessages) {
          tdlib.send(new TdApi.ToggleChatViewAsTopics(chatId(), false), tdlib.typedOkHandler());
          openChatAsMessages();
        } else if (optionId == R.id.btn_openChatProfile) {
          tdlib.ui().openChatProfile(this, chatId(), null, null);
        }
        return true;
      });
    }
  }

  private void openChatAsMessages () {
    TdlibUi.ChatOpenParameters params = new TdlibUi.ChatOpenParameters().keepStack();
    tdlib.ui().openChat(this, getArgumentsStrict().chat, params);
  }

  // Click

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_forumTopic) {
      ListItem item = (ListItem) v.getTag();
      if (item != null && item.getData() instanceof TdApi.ForumTopic) {
        openTopic((TdApi.ForumTopic) item.getData());
      }
    }
  }

  private void openTopic (TdApi.ForumTopic topic) {
    tdlib.ui().openForumTopic(this, tdlib.chatStrict(chatId()), getArgumentsStrict().chatList, topic);
  }

}
