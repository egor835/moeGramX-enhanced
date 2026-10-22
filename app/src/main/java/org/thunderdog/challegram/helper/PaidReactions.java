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
package org.thunderdog.challegram.helper;

import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;

import java.util.HashMap;
import java.util.Map;

/**
 * Sending of paid reactions (Telegram Stars).
 *
 * A tap on the star reaction adds one pending star. Pending stars are accumulated and committed
 * after {@link #COMMIT_DELAY_MS}, so the user can tap several times or undo the reaction, the same way
 * official apps do.
 */
public final class PaidReactions {
  public static final long COMMIT_DELAY_MS = 5000L;
  public static final long DEFAULT_STAR_COUNT = 50L;

  private PaidReactions () { }

  public interface PendingListener {
    void onPendingStarCountChanged (long pendingStarCount);
  }

  private static class Pending {
    final Tdlib tdlib;
    final long chatId, messageId;
    long starCount;
    Runnable commitAction;

    Pending (Tdlib tdlib, long chatId, long messageId) {
      this.tdlib = tdlib;
      this.chatId = chatId;
      this.messageId = messageId;
    }
  }

  private static final Map<String, Pending> pendingReactions = new HashMap<>();

  private static String makeKey (Tdlib tdlib, long chatId, long messageId) {
    return tdlib.id() + "_" + chatId + "_" + messageId;
  }

  @UiThread
  public static long getPendingStarCount (Tdlib tdlib, long chatId, long messageId) {
    Pending pending = pendingReactions.get(makeKey(tdlib, chatId, messageId));
    return pending != null ? pending.starCount : 0;
  }

  public static long getMaxStarCount (Tdlib tdlib) {
    long max = tdlib.options().paidReactionStarCountMax;
    return max > 0 ? max : 2500L;
  }

  /**
   * @return false, if the user doesn't have enough stars
   */
  @UiThread
  public static boolean checkBalance (Tdlib tdlib, long starCount) {
    long balance = tdlib.ownedStarCount();
    if (balance >= 0 && balance < starCount) {
      UI.showToast(Lang.getString(R.string.PaidReactionNotEnoughStars, Lang.formatNumber(balance)), Toast.LENGTH_LONG);
      return false;
    }
    return true;
  }

  /**
   * Adds stars to the paid reaction of the message.
   *
   * @param type Type of the paid reaction, or null if it wasn't chosen explicitly
   * @param commitImmediately Whether the reaction must be sent right away (for example, from the star picker)
   * @return false, if the reaction wasn't added
   */
  @UiThread
  public static boolean addStars (Tdlib tdlib, long chatId, long messageId, long starCount, @Nullable TdApi.PaidReactionType type, boolean commitImmediately, @Nullable PendingListener listener) {
    final String key = makeKey(tdlib, chatId, messageId);
    Pending pending = pendingReactions.get(key);
    long alreadyPending = pending != null ? pending.starCount : 0;
    if (!checkBalance(tdlib, alreadyPending + starCount)) {
      return false;
    }
    if (alreadyPending + starCount > getMaxStarCount(tdlib)) {
      UI.showToast(Lang.getString(R.string.PaidReactionLimitReached, Lang.formatNumber(getMaxStarCount(tdlib))), Toast.LENGTH_SHORT);
      return false;
    }
    if (pending == null) {
      pending = new Pending(tdlib, chatId, messageId);
      pendingReactions.put(key, pending);
    }
    pending.starCount += starCount;
    final Pending finalPending = pending;
    if (finalPending.commitAction != null) {
      UI.cancel(finalPending.commitAction);
      finalPending.commitAction = null;
    }
    if (!commitImmediately) {
      finalPending.commitAction = () -> commit(tdlib, chatId, messageId);
      UI.post(finalPending.commitAction, COMMIT_DELAY_MS);
    }
    if (listener != null) {
      listener.onPendingStarCountChanged(finalPending.starCount);
    }
    tdlib.send(new TdApi.AddPendingPaidMessageReaction(chatId, messageId, starCount, type), (ok, error) -> UI.post(() -> {
      if (error != null) {
        if (pendingReactions.get(key) == finalPending) {
          finalPending.starCount = Math.max(0, finalPending.starCount - starCount);
          if (finalPending.starCount == 0) {
            if (finalPending.commitAction != null) {
              UI.cancel(finalPending.commitAction);
            }
            pendingReactions.remove(key);
          }
          if (listener != null) {
            listener.onPendingStarCountChanged(finalPending.starCount);
          }
        }
        showError(error);
      } else if (commitImmediately) {
        commit(tdlib, chatId, messageId);
      }
    }));
    return true;
  }

  @UiThread
  public static void commit (Tdlib tdlib, long chatId, long messageId) {
    Pending pending = pendingReactions.remove(makeKey(tdlib, chatId, messageId));
    if (pending != null && pending.commitAction != null) {
      UI.cancel(pending.commitAction);
      pending.commitAction = null;
    }
    tdlib.send(new TdApi.CommitPendingPaidMessageReactions(chatId, messageId), (ok, error) -> {
      // TDLib may have already sent pending reactions on its own, nothing to do in this case
      if (error != null && !isNothingToCommitError(error)) {
        UI.post(() -> showError(error));
      }
    });
  }

  @UiThread
  public static void undo (Tdlib tdlib, long chatId, long messageId) {
    Pending pending = pendingReactions.remove(makeKey(tdlib, chatId, messageId));
    if (pending == null) {
      return;
    }
    if (pending.commitAction != null) {
      UI.cancel(pending.commitAction);
      pending.commitAction = null;
    }
    tdlib.send(new TdApi.RemovePendingPaidMessageReactions(chatId, messageId), (ok, error) -> {
      if (error != null) {
        UI.post(() -> showError(error));
      }
    });
  }

  public static void setReactionType (Tdlib tdlib, long chatId, long messageId, TdApi.PaidReactionType type) {
    tdlib.send(new TdApi.SetPaidMessageReactionType(chatId, messageId, type), (ok, error) -> {
      if (error != null) {
        UI.post(() -> showError(error));
      }
    });
  }

  private static boolean isNothingToCommitError (TdApi.Error error) {
    return error.code == 400 && error.message != null && error.message.toLowerCase().contains("pending");
  }

  private static void showError (TdApi.Error error) {
    String message = error.message != null ? error.message : "";
    if (message.contains("BALANCE_TOO_LOW") || message.contains("STARS_INSUFFICIENT")) {
      UI.showToast(Lang.getString(R.string.PaidReactionNotEnoughStarsUnknown), Toast.LENGTH_LONG);
    } else {
      UI.showError(error);
    }
  }

  public static boolean isPaid (@Nullable TdApi.ReactionType type) {
    return type != null && type.getConstructor() == TdApi.ReactionTypePaid.CONSTRUCTOR;
  }

  /**
   * @return Information about the current user among paid reactors, if any
   */
  @Nullable
  public static TdApi.PaidReactor findMyReactor (@Nullable TdApi.MessageReactions reactions) {
    if (reactions != null && reactions.paidReactors != null) {
      for (TdApi.PaidReactor reactor : reactions.paidReactors) {
        if (reactor.isMe) {
          return reactor;
        }
      }
    }
    return null;
  }
}
