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
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.helper.PaidReactions;
import org.thunderdog.challegram.mediaview.SliderView;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.CheckBoxView;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.ShadowView;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Popup that allows choosing how many Telegram Stars to send as a paid reaction.
 */
public class PaidReactionPicker {
  private static final int MAX_TOP_SENDERS = 3;

  private final MessagesController owner;
  private final Context context;
  private final Tdlib tdlib;
  private final long chatId, messageId;
  private final long maxStarCount;
  private final @Nullable TdApi.MessageReactions reactions;
  private final @Nullable TdApi.PaidReactor myReactor;

  private long starCount;
  private boolean isAnonymous;

  private PopupLayout popupLayout;
  private TextView amountView, sendButton, balanceView;

  public PaidReactionPicker (MessagesController owner, TGMessage message) {
    this.owner = owner;
    this.context = owner.context();
    this.tdlib = owner.tdlib();
    this.chatId = message.getChatId();
    this.messageId = message.getOldestMessage().id;
    this.maxStarCount = PaidReactions.getMaxStarCount(tdlib);
    this.reactions = message.getMessageReactions().getReactions();
    this.myReactor = PaidReactions.findMyReactor(reactions);

    if (myReactor != null) {
      this.isAnonymous = myReactor.isAnonymous;
    } else {
      TdApi.PaidReactionType defaultType = tdlib.defaultPaidReaction();
      this.isAnonymous = defaultType != null && defaultType.getConstructor() == TdApi.PaidReactionTypeAnonymous.CONSTRUCTOR;
    }

    long initialCount = Math.min(PaidReactions.DEFAULT_STAR_COUNT, maxStarCount);
    long balance = tdlib.ownedStarCount();
    if (balance > 0 && balance < initialCount) {
      initialCount = balance;
    }
    this.starCount = Math.max(1, initialCount);
  }

  // Slider mapping (logarithmic, so small amounts are easy to pick)

  private float toFactor (long count) {
    if (maxStarCount <= 1) {
      return 1f;
    }
    return (float) (Math.log(Math.max(1, count)) / Math.log(maxStarCount));
  }

  private long fromFactor (float factor) {
    long count = Math.round(Math.pow(maxStarCount, Math.max(0f, Math.min(1f, factor))));
    if (count > 1000) {
      count = Math.round(count / 100.0) * 100;
    } else if (count > 100) {
      count = Math.round(count / 50.0) * 50;
    } else if (count > 10) {
      count = Math.round(count / 5.0) * 5;
    }
    return Math.max(1, Math.min(maxStarCount, count));
  }

  // UI

  private TextView newText (float textSizeDp, @ColorId int colorId, boolean medium) {
    TextView view = new TextView(context);
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
    view.setTextColor(Theme.getColor(colorId));
    view.setTypeface(medium ? Fonts.getRobotoMedium() : Fonts.getRobotoRegular());
    return view;
  }

  private Drawable newStar (float sizeDp) {
    Drawable star = Drawables.get(R.drawable.baseline_paid_reaction_star_24);
    int size = Screen.dp(sizeDp);
    star.setBounds(0, 0, size, size);
    return star;
  }

  private String senderName (TdApi.PaidReactor reactor) {
    if (reactor.isAnonymous || reactor.senderId == null) {
      return reactor.isMe ? Lang.getString(R.string.PaidReactionYouAnonymous) : Lang.getString(R.string.PaidReactionAnonymous);
    }
    return tdlib.senderName(reactor.senderId, true);
  }

  private View buildView () {
    final int horizontalPadding = Screen.dp(16f);

    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(horizontalPadding, Screen.dp(16f), horizontalPadding, Screen.dp(16f));
    content.setBackgroundColor(Theme.fillingColor());

    // Title + balance

    FrameLayoutFix titleWrap = new FrameLayoutFix(context);
    TextView titleView = newText(18f, ColorId.text, true);
    titleView.setText(Lang.getString(R.string.PaidReaction));
    titleWrap.addView(titleView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT));
    balanceView = newText(13f, ColorId.textLight, false);
    titleWrap.addView(balanceView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
    content.addView(titleWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    updateBalance();

    // Description

    TextView descriptionView = newText(14f, ColorId.textLight, false);
    descriptionView.setText(Lang.getString(R.string.PaidReactionDescription, tdlib.chatTitle(chatId)));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = Screen.dp(6f);
    content.addView(descriptionView, params);

    // Amount

    amountView = newText(30f, ColorId.text, true);
    amountView.setGravity(Gravity.CENTER);
    amountView.setCompoundDrawablePadding(Screen.dp(6f));
    amountView.setCompoundDrawables(newStar(30f), null, null, null);
    params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.CENTER_HORIZONTAL;
    params.topMargin = Screen.dp(18f);
    content.addView(amountView, params);

    // Slider

    SliderView sliderView = new SliderView(context);
    sliderView.setSlideEnabled(true, false);
    sliderView.setForceBackgroundColorId(ColorId.sliderInactive);
    sliderView.setColorId(ColorId.sliderActive, false);
    sliderView.setValue(toFactor(starCount));
    sliderView.setListener(new SliderView.Listener() {
      @Override
      public void onSetStateChanged (SliderView view, boolean isSetting) { }

      @Override
      public void onValueChanged (SliderView view, float factor) {
        long newCount = fromFactor(factor);
        if (newCount != starCount) {
          starCount = newCount;
          updateAmount();
        }
      }

      @Override
      public boolean allowSliderChanges (SliderView view) {
        return true;
      }
    });
    params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(40f));
    params.topMargin = Screen.dp(8f);
    content.addView(sliderView, params);

    // Top senders

    if (reactions != null && reactions.paidReactors != null && reactions.paidReactors.length > 0) {
      TextView topHeader = newText(14f, ColorId.textNeutral, true);
      topHeader.setText(Lang.getString(R.string.PaidReactionTopSenders));
      params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.topMargin = Screen.dp(12f);
      content.addView(topHeader, params);

      int index = 0;
      for (TdApi.PaidReactor reactor : reactions.paidReactors) {
        if (index >= MAX_TOP_SENDERS) {
          break;
        }
        index++;
        FrameLayoutFix row = new FrameLayoutFix(context);
        TextView nameView = newText(15f, ColorId.text, reactor.isMe);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setText(index + ". " + senderName(reactor));
        nameView.setPadding(Lang.rtl() ? Screen.dp(80f) : 0, 0, Lang.rtl() ? 0 : Screen.dp(80f), 0);
        row.addView(nameView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        TextView countView = newText(15f, ColorId.textLight, false);
        countView.setText(Lang.formatNumber(reactor.starCount));
        countView.setCompoundDrawablePadding(Screen.dp(4f));
        countView.setCompoundDrawables(newStar(16f), null, null, null);
        row.addView(countView, FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(32f));
        content.addView(row, params);
      }
    }

    // Show in top senders

    FrameLayoutFix anonymityRow = new FrameLayoutFix(context);
    TextView anonymityText = newText(15f, ColorId.text, false);
    anonymityText.setText(Lang.getString(R.string.PaidReactionShowMe));
    anonymityText.setPadding(Lang.rtl() ? Screen.dp(40f) : 0, 0, Lang.rtl() ? 0 : Screen.dp(40f), 0);
    anonymityRow.addView(anonymityText, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    CheckBoxView checkBox = CheckBoxView.simpleCheckBox(context);
    FrameLayoutFix.LayoutParams checkParams = (FrameLayoutFix.LayoutParams) checkBox.getLayoutParams();
    checkParams.leftMargin = checkParams.rightMargin = Screen.dp(2f);
    checkBox.setChecked(!isAnonymous, false);
    anonymityRow.addView(checkBox);
    Views.setClickable(anonymityRow);
    anonymityRow.setOnClickListener(v -> {
      isAnonymous = !isAnonymous;
      checkBox.setChecked(!isAnonymous, true);
      if (myReactor != null) {
        // Apply new privacy setting to already sent stars
        PaidReactions.setReactionType(tdlib, chatId, messageId, newReactionType());
      }
    });
    params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f));
    params.topMargin = Screen.dp(8f);
    content.addView(anonymityRow, params);

    // Send button

    sendButton = newText(16f, ColorId.fillingPositiveContent, true);
    sendButton.setGravity(Gravity.CENTER);
    sendButton.setTextColor(Theme.getColor(ColorId.fillingPositiveContent));
    sendButton.setBackground(Drawables.createRoundDrawable(Screen.dp(8f), Theme.getColor(ColorId.fillingPositive)));
    Views.setClickable(sendButton);
    sendButton.setOnClickListener(v -> send());
    params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f));
    params.topMargin = Screen.dp(12f);
    content.addView(sendButton, params);

    updateAmount();

    // Shadow + content

    ShadowView shadowView = new ShadowView(context);
    shadowView.setSimpleTopShadow(true);

    LinearLayout popupView = new LinearLayout(context);
    popupView.setOrientation(LinearLayout.VERTICAL);
    popupView.addView(shadowView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(7f)));
    popupView.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    popupView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    return popupView;
  }

  private TdApi.PaidReactionType newReactionType () {
    return isAnonymous ? new TdApi.PaidReactionTypeAnonymous() : new TdApi.PaidReactionTypeRegular();
  }

  private void updateAmount () {
    if (amountView != null) {
      amountView.setText(Lang.formatNumber(starCount));
    }
    if (sendButton != null) {
      sendButton.setText(Lang.getString(R.string.PaidReactionSend, Lang.formatNumber(starCount)));
      long balance = tdlib.ownedStarCount();
      boolean enough = balance < 0 || balance >= starCount;
      sendButton.setAlpha(enough ? 1f : .6f);
    }
  }

  private void updateBalance () {
    if (balanceView == null) {
      return;
    }
    long balance = tdlib.ownedStarCount();
    if (balance >= 0) {
      balanceView.setText(Lang.getString(R.string.PaidReactionBalance, Lang.formatNumber(balance)));
      balanceView.setVisibility(View.VISIBLE);
    } else {
      balanceView.setVisibility(View.GONE);
    }
  }

  private void requestBalance () {
    if (tdlib.ownedStarCount() >= 0) {
      return;
    }
    // The balance is usually received through updateOwnedStarCount, request it explicitly otherwise
    tdlib.send(new TdApi.GetStarTransactions(new TdApi.MessageSenderUser(tdlib.myUserId()), null, null, null, 1), (transactions, error) -> {
      if (transactions != null) {
        tdlib.onStarAmountReceived(transactions.starAmount);
        UI.post(() -> {
          updateBalance();
          updateAmount();
        });
      }
    });
  }

  private void send () {
    if (!PaidReactions.checkBalance(tdlib, starCount)) {
      return;
    }
    if (PaidReactions.addStars(tdlib, chatId, messageId, starCount, newReactionType(), true, null)) {
      hide();
    }
  }

  public void show () {
    View popupView = buildView();
    int width = Screen.currentWidth();
    popupView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int popupHeight = popupView.getMeasuredHeight();

    popupLayout = new PopupLayout(context);
    popupLayout.init(true);
    popupLayout.setHideKeyboard();
    popupLayout.setNeedRootInsets();
    popupLayout.showSimplePopupView(popupView, popupHeight);
    requestBalance();
  }

  public void hide () {
    if (popupLayout != null) {
      popupLayout.hideWindow(true);
      popupLayout = null;
    }
  }
}
