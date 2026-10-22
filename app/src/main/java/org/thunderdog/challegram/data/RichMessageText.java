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
package org.thunderdog.challegram.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

/**
 * Converts {@link TdApi.RichMessage} (blocks produced from Markdown / HTML by bots) into
 * {@link TdApi.FormattedText}, so it can be displayed by a regular text bubble.
 */
public final class RichMessageText {
  private static final int MAX_MONOSPACE_TABLE_WIDTH = 42;

  private final StringBuilder text = new StringBuilder();
  private final List<TdApi.TextEntity> entities = new ArrayList<>();
  private final boolean plain;
  private final boolean skipMedia;
  private int quoteDepth;
  private int preDepth;

  private RichMessageText (boolean plain) {
    this(plain, false);
  }

  private RichMessageText (boolean plain, boolean skipMedia) {
    this.plain = plain;
    this.skipMedia = skipMedia;
  }

  @NonNull
  public static TdApi.FormattedText toFormattedText (@Nullable TdApi.RichMessage message) {
    return toFormattedText(message, false);
  }

  /**
   * @param skipMedia Whether photos, videos and animations must be omitted, because they are displayed separately
   */
  @NonNull
  public static TdApi.FormattedText toFormattedText (@Nullable TdApi.RichMessage message, boolean skipMedia) {
    RichMessageText builder = new RichMessageText(false, skipMedia);
    if (message != null && message.blocks != null) {
      builder.appendBlocks(message.blocks, 0);
    }
    return builder.build();
  }

  @NonNull
  public static TdApi.FormattedText toFormattedText (@Nullable TdApi.MessageRichMessage content) {
    return toFormattedText(content != null ? content.message : null);
  }

  // Media

  public static final int MAX_MEDIA_COUNT = 10;

  /**
   * @return Photos, videos and animations of the rich message, which can be displayed as an album
   */
  @NonNull
  public static List<TdApi.MessageContent> collectMedia (@Nullable TdApi.RichMessage message) {
    List<TdApi.MessageContent> result = new ArrayList<>();
    if (message != null && message.blocks != null) {
      collectMedia(message.blocks, result);
    }
    return result;
  }

  private static void collectMedia (@Nullable TdApi.PageBlock[] blocks, List<TdApi.MessageContent> out) {
    if (blocks == null) {
      return;
    }
    for (TdApi.PageBlock block : blocks) {
      if (out.size() >= MAX_MEDIA_COUNT) {
        return;
      }
      if (block == null) {
        continue;
      }
      TdApi.MessageContent content = toMediaContent(block);
      if (content != null) {
        out.add(content);
        continue;
      }
      switch (block.getConstructor()) {
        case TdApi.PageBlockCollage.CONSTRUCTOR:
          collectMedia(((TdApi.PageBlockCollage) block).blocks, out);
          break;
        case TdApi.PageBlockSlideshow.CONSTRUCTOR:
          collectMedia(((TdApi.PageBlockSlideshow) block).blocks, out);
          break;
        case TdApi.PageBlockCover.CONSTRUCTOR:
          collectMedia(new TdApi.PageBlock[] {((TdApi.PageBlockCover) block).cover}, out);
          break;
        case TdApi.PageBlockBlockQuote.CONSTRUCTOR:
          collectMedia(((TdApi.PageBlockBlockQuote) block).blocks, out);
          break;
        case TdApi.PageBlockDetails.CONSTRUCTOR:
          collectMedia(((TdApi.PageBlockDetails) block).blocks, out);
          break;
      }
    }
  }

  @Nullable
  private static TdApi.MessageContent toMediaContent (TdApi.PageBlock block) {
    final TdApi.FormattedText noCaption = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR: {
        TdApi.PageBlockPhoto photo = (TdApi.PageBlockPhoto) block;
        if (photo.photo == null || photo.photo.sizes == null || photo.photo.sizes.length == 0) {
          return null;
        }
        return new TdApi.MessagePhoto(photo.photo, null, noCaption, false, photo.hasSpoiler, false);
      }
      case TdApi.PageBlockVideo.CONSTRUCTOR: {
        TdApi.PageBlockVideo video = (TdApi.PageBlockVideo) block;
        if (video.video == null) {
          return null;
        }
        return new TdApi.MessageVideo(video.video, new TdApi.AlternativeVideo[0], new TdApi.VideoStoryboard[0], null, 0, noCaption, false, video.hasSpoiler, false);
      }
      case TdApi.PageBlockAnimation.CONSTRUCTOR: {
        TdApi.PageBlockAnimation animation = (TdApi.PageBlockAnimation) block;
        if (animation.animation == null) {
          return null;
        }
        return new TdApi.MessageAnimation(animation.animation, noCaption, false, animation.hasSpoiler, false);
      }
    }
    return null;
  }

  private boolean isDisplayedSeparately (TdApi.PageBlock block) {
    return skipMedia && toMediaContent(block) != null;
  }

  /**
   * Text representation without any formatting, used for chat list previews and notifications.
   */
  @NonNull
  public static String toPlainText (@Nullable TdApi.RichMessage message) {
    RichMessageText builder = new RichMessageText(true);
    if (message != null && message.blocks != null) {
      builder.appendBlocks(message.blocks, 0);
    }
    return builder.text.toString().trim();
  }

  @NonNull
  private TdApi.FormattedText build () {
    // Trim trailing whitespace, keeping entities in bounds
    int length = text.length();
    while (length > 0 && Character.isWhitespace(text.charAt(length - 1))) {
      length--;
    }
    text.setLength(length);
    List<TdApi.TextEntity> result = new ArrayList<>(entities.size());
    for (TdApi.TextEntity entity : entities) {
      int end = Math.min(entity.offset + entity.length, length);
      if (entity.offset < end) {
        entity.length = end - entity.offset;
        result.add(entity);
      }
    }
    // Outer entities must go first
    Collections.sort(result, (a, b) -> a.offset != b.offset ? Integer.compare(a.offset, b.offset) : Integer.compare(b.length, a.length));
    return new TdApi.FormattedText(text.toString(), result.toArray(new TdApi.TextEntity[0]));
  }

  // Helpers

  private void addEntity (int start, TdApi.TextEntityType type) {
    if (plain) {
      return;
    }
    int length = text.length() - start;
    if (length > 0) {
      entities.add(new TdApi.TextEntity(start, length, type));
    }
  }

  private void addFormattingEntity (int start, TdApi.TextEntityType type) {
    // Formatting inside code blocks is not allowed
    if (preDepth == 0) {
      addEntity(start, type);
    }
  }

  private void newLine () {
    if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
      text.append('\n');
    }
  }

  private void blockSeparator () {
    newLine();
  }

  private void headingSeparator () {
    int length = text.length();
    if (length == 0) {
      return;
    }
    if (text.charAt(length - 1) != '\n') {
      text.append("\n\n");
    } else if (length < 2 || text.charAt(length - 2) != '\n') {
      text.append('\n');
    }
  }

  private static boolean isEmpty (@Nullable TdApi.RichText text) {
    if (text == null) {
      return true;
    }
    switch (text.getConstructor()) {
      case TdApi.RichTextPlain.CONSTRUCTOR:
        return StringUtils.isEmpty(((TdApi.RichTextPlain) text).text);
      case TdApi.RichTexts.CONSTRUCTOR: {
        for (TdApi.RichText child : ((TdApi.RichTexts) text).texts) {
          if (!isEmpty(child)) {
            return false;
          }
        }
        return true;
      }
      case TdApi.RichTextAnchor.CONSTRUCTOR:
        return true;
    }
    return false;
  }

  @NonNull
  private static String plainTextOf (@Nullable TdApi.RichText text) {
    RichMessageText builder = new RichMessageText(true);
    builder.appendText(text);
    return builder.text.toString();
  }

  // Rich texts

  private void appendText (@Nullable TdApi.RichText richText) {
    if (richText == null) {
      return;
    }
    final int start = text.length();
    switch (richText.getConstructor()) {
      case TdApi.RichTextPlain.CONSTRUCTOR: {
        String value = ((TdApi.RichTextPlain) richText).text;
        if (value != null) {
          text.append(value);
        }
        break;
      }
      case TdApi.RichTexts.CONSTRUCTOR: {
        for (TdApi.RichText child : ((TdApi.RichTexts) richText).texts) {
          appendText(child);
        }
        break;
      }
      case TdApi.RichTextBold.CONSTRUCTOR: {
        appendText(((TdApi.RichTextBold) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeBold());
        break;
      }
      case TdApi.RichTextItalic.CONSTRUCTOR: {
        appendText(((TdApi.RichTextItalic) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
        break;
      }
      case TdApi.RichTextUnderline.CONSTRUCTOR: {
        appendText(((TdApi.RichTextUnderline) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeUnderline());
        break;
      }
      case TdApi.RichTextStrikethrough.CONSTRUCTOR: {
        appendText(((TdApi.RichTextStrikethrough) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeStrikethrough());
        break;
      }
      case TdApi.RichTextSpoiler.CONSTRUCTOR: {
        appendText(((TdApi.RichTextSpoiler) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeSpoiler());
        break;
      }
      case TdApi.RichTextMarked.CONSTRUCTOR: {
        appendText(((TdApi.RichTextMarked) richText).text);
        addFormattingEntity(start, new TdApi.TextEntityTypeBold());
        break;
      }
      case TdApi.RichTextSubscript.CONSTRUCTOR: {
        appendText(((TdApi.RichTextSubscript) richText).text);
        break;
      }
      case TdApi.RichTextSuperscript.CONSTRUCTOR: {
        appendText(((TdApi.RichTextSuperscript) richText).text);
        break;
      }
      case TdApi.RichTextFixed.CONSTRUCTOR: {
        preDepth++;
        appendText(((TdApi.RichTextFixed) richText).text);
        preDepth--;
        addFormattingEntity(start, new TdApi.TextEntityTypeCode());
        break;
      }
      case TdApi.RichTextDateTime.CONSTRUCTOR: {
        TdApi.RichTextDateTime dateTime = (TdApi.RichTextDateTime) richText;
        appendText(dateTime.text);
        if (text.length() == start && dateTime.unixTime != 0) {
          text.append(Lang.getTimestamp(dateTime.unixTime, TimeUnit.SECONDS));
        }
        addFormattingEntity(start, new TdApi.TextEntityTypeDateTime(dateTime.unixTime, dateTime.formattingType != null ? dateTime.formattingType : new TdApi.DateTimeFormattingTypeRelative()));
        break;
      }
      case TdApi.RichTextMention.CONSTRUCTOR: {
        TdApi.RichTextMention mention = (TdApi.RichTextMention) richText;
        appendText(mention.text);
        if (text.length() == start) {
          text.append('@').append(mention.username);
        }
        String value = text.substring(start);
        if (value.equals("@" + mention.username)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeMention());
        } else {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl("https://t.me/" + mention.username));
        }
        break;
      }
      case TdApi.RichTextHashtag.CONSTRUCTOR: {
        TdApi.RichTextHashtag hashtag = (TdApi.RichTextHashtag) richText;
        appendSpecialText(start, hashtag.text, hashtag.hashtag, new TdApi.TextEntityTypeHashtag());
        break;
      }
      case TdApi.RichTextCashtag.CONSTRUCTOR: {
        TdApi.RichTextCashtag cashtag = (TdApi.RichTextCashtag) richText;
        appendSpecialText(start, cashtag.text, cashtag.cashtag, new TdApi.TextEntityTypeCashtag());
        break;
      }
      case TdApi.RichTextBotCommand.CONSTRUCTOR: {
        TdApi.RichTextBotCommand command = (TdApi.RichTextBotCommand) richText;
        appendSpecialText(start, command.text, command.botCommand, new TdApi.TextEntityTypeBotCommand());
        break;
      }
      case TdApi.RichTextBankCardNumber.CONSTRUCTOR: {
        TdApi.RichTextBankCardNumber card = (TdApi.RichTextBankCardNumber) richText;
        appendSpecialText(start, card.text, card.bankCardNumber, new TdApi.TextEntityTypeBankCardNumber());
        break;
      }
      case TdApi.RichTextEmailAddress.CONSTRUCTOR: {
        TdApi.RichTextEmailAddress email = (TdApi.RichTextEmailAddress) richText;
        appendText(email.text);
        if (text.length() == start) {
          text.append(email.emailAddress);
        }
        if (text.substring(start).equals(email.emailAddress)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeEmailAddress());
        } else {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl("mailto:" + email.emailAddress));
        }
        break;
      }
      case TdApi.RichTextPhoneNumber.CONSTRUCTOR: {
        TdApi.RichTextPhoneNumber phone = (TdApi.RichTextPhoneNumber) richText;
        appendText(phone.text);
        if (text.length() == start) {
          text.append(phone.phoneNumber);
        }
        if (text.substring(start).equals(phone.phoneNumber)) {
          addFormattingEntity(start, new TdApi.TextEntityTypePhoneNumber());
        } else {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl("tel:" + phone.phoneNumber));
        }
        break;
      }
      case TdApi.RichTextMentionName.CONSTRUCTOR: {
        TdApi.RichTextMentionName mentionName = (TdApi.RichTextMentionName) richText;
        appendText(mentionName.text);
        addFormattingEntity(start, new TdApi.TextEntityTypeMentionName(mentionName.userId));
        break;
      }
      case TdApi.RichTextUrl.CONSTRUCTOR: {
        TdApi.RichTextUrl url = (TdApi.RichTextUrl) richText;
        appendText(url.text);
        if (text.length() == start && !StringUtils.isEmpty(url.url)) {
          text.append(url.url);
        }
        if (!StringUtils.isEmpty(url.url)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl(url.url));
        }
        break;
      }
      case TdApi.RichTextCustomEmoji.CONSTRUCTOR: {
        TdApi.RichTextCustomEmoji customEmoji = (TdApi.RichTextCustomEmoji) richText;
        String alternativeText = StringUtils.isEmpty(customEmoji.alternativeText) ? "❓" : customEmoji.alternativeText;
        text.append(alternativeText);
        if (preDepth == 0) {
          addEntity(start, new TdApi.TextEntityTypeCustomEmoji(customEmoji.customEmojiId));
        }
        break;
      }
      case TdApi.RichTextIcon.CONSTRUCTOR: {
        // Inline images can't be displayed inside a text
        break;
      }
      case TdApi.RichTextMathematicalExpression.CONSTRUCTOR: {
        text.append(((TdApi.RichTextMathematicalExpression) richText).expression);
        addFormattingEntity(start, new TdApi.TextEntityTypeCode());
        break;
      }
      case TdApi.RichTextButton.CONSTRUCTOR: {
        appendButton(((TdApi.RichTextButton) richText).button);
        break;
      }
      case TdApi.RichTextDiff.CONSTRUCTOR: {
        TdApi.RichTextDiff diff = (TdApi.RichTextDiff) richText;
        if (!isEmpty(diff.oldText)) {
          appendText(diff.oldText);
          addFormattingEntity(start, new TdApi.TextEntityTypeStrikethrough());
          text.append(' ');
        }
        int newStart = text.length();
        appendText(diff.text);
        addFormattingEntity(newStart, new TdApi.TextEntityTypeUnderline());
        break;
      }
      case TdApi.RichTextReference.CONSTRUCTOR: {
        appendText(((TdApi.RichTextReference) richText).text);
        break;
      }
      case TdApi.RichTextReferenceLink.CONSTRUCTOR: {
        TdApi.RichTextReferenceLink link = (TdApi.RichTextReferenceLink) richText;
        appendText(link.text);
        if (!StringUtils.isEmpty(link.url)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl(link.url));
        }
        break;
      }
      case TdApi.RichTextAnchor.CONSTRUCTOR: {
        break;
      }
      case TdApi.RichTextAnchorLink.CONSTRUCTOR: {
        TdApi.RichTextAnchorLink link = (TdApi.RichTextAnchorLink) richText;
        appendText(link.text);
        if (!StringUtils.isEmpty(link.url)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl(link.url));
        }
        break;
      }
      default: {
        // Unknown rich text type, ignore it
        break;
      }
    }
  }

  private void appendSpecialText (int start, TdApi.RichText richText, String value, TdApi.TextEntityType type) {
    appendText(richText);
    if (text.length() == start && value != null) {
      text.append(value);
    }
    if (value != null && text.substring(start).equals(value)) {
      addFormattingEntity(start, type);
    }
  }

  private void appendButton (@Nullable TdApi.InlineButton button) {
    if (button == null) {
      return;
    }
    final int start = text.length();
    String label = plainTextOf(button.text);
    if (button.type != null && button.type.getConstructor() == TdApi.InlineKeyboardButtonTypeUrl.CONSTRUCTOR) {
      text.append(label);
      addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl(((TdApi.InlineKeyboardButtonTypeUrl) button.type).url));
    } else {
      text.append('[').append(label).append(']');
      addFormattingEntity(start, new TdApi.TextEntityTypeBold());
    }
  }

  // Blocks

  private void appendBlocks (@Nullable TdApi.PageBlock[] blocks, int depth) {
    if (blocks == null) {
      return;
    }
    for (TdApi.PageBlock block : blocks) {
      appendBlock(block, depth);
    }
  }

  private void appendStyledBlock (@Nullable TdApi.RichText richText, @Nullable TdApi.TextEntityType type) {
    appendStyledBlock(richText, type, false);
  }

  private void appendStyledBlock (@Nullable TdApi.RichText richText, @Nullable TdApi.TextEntityType type, boolean isHeading) {
    if (isEmpty(richText)) {
      return;
    }
    if (isHeading && quoteDepth == 0) {
      headingSeparator();
    } else {
      blockSeparator();
    }
    int start = text.length();
    appendText(richText);
    if (type != null) {
      addFormattingEntity(start, type);
    }
  }

  private void appendQuoted (Runnable content, boolean expandable) {
    int start = text.length();
    quoteDepth++;
    content.run();
    quoteDepth--;
    // Quotes can't be nested
    if (quoteDepth == 0 && preDepth == 0) {
      addEntity(start, expandable ? new TdApi.TextEntityTypeExpandableBlockQuote() : new TdApi.TextEntityTypeBlockQuote());
    }
  }

  private void appendMedia (String label, @Nullable TdApi.PageBlockCaption caption) {
    blockSeparator();
    int start = text.length();
    text.append(label);
    addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
    appendCaption(caption);
  }

  private void appendCaption (@Nullable TdApi.PageBlockCaption caption) {
    if (caption == null) {
      return;
    }
    if (!isEmpty(caption.text)) {
      newLine();
      appendText(caption.text);
    }
    if (!isEmpty(caption.credit)) {
      newLine();
      int start = text.length();
      appendText(caption.credit);
      addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
    }
  }

  private void appendBlock (@Nullable TdApi.PageBlock block, int depth) {
    if (block == null) {
      return;
    }
    switch (block.getConstructor()) {
      case TdApi.PageBlockTitle.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockTitle) block).title, new TdApi.TextEntityTypeBold(), true);
        break;
      case TdApi.PageBlockSubtitle.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockSubtitle) block).subtitle, new TdApi.TextEntityTypeItalic());
        break;
      case TdApi.PageBlockHeader.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockHeader) block).header, new TdApi.TextEntityTypeBold(), true);
        break;
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockSubheader) block).subheader, new TdApi.TextEntityTypeBold(), true);
        break;
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockSectionHeading) block).text, new TdApi.TextEntityTypeBold(), true);
        break;
      case TdApi.PageBlockKicker.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockKicker) block).kicker, new TdApi.TextEntityTypeItalic());
        break;
      case TdApi.PageBlockParagraph.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockParagraph) block).text, null);
        break;
      case TdApi.PageBlockFooter.CONSTRUCTOR:
        appendStyledBlock(((TdApi.PageBlockFooter) block).footer, new TdApi.TextEntityTypeItalic());
        break;
      case TdApi.PageBlockAuthorDate.CONSTRUCTOR: {
        TdApi.PageBlockAuthorDate authorDate = (TdApi.PageBlockAuthorDate) block;
        blockSeparator();
        int start = text.length();
        appendText(authorDate.author);
        if (authorDate.publishDate != 0) {
          if (text.length() > start) {
            text.append(" • ");
          }
          text.append(Lang.getDate(authorDate.publishDate, TimeUnit.SECONDS));
        }
        addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
        break;
      }
      case TdApi.PageBlockPreformatted.CONSTRUCTOR: {
        TdApi.PageBlockPreformatted preformatted = (TdApi.PageBlockPreformatted) block;
        blockSeparator();
        int start = text.length();
        text.append(plainTextOf(preformatted.text));
        if (preDepth == 0) {
          addEntity(start, StringUtils.isEmpty(preformatted.language) ? new TdApi.TextEntityTypePre() : new TdApi.TextEntityTypePreCode(preformatted.language));
        }
        break;
      }
      case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR: {
        blockSeparator();
        int start = text.length();
        text.append(((TdApi.PageBlockMathematicalExpression) block).expression);
        if (preDepth == 0) {
          addEntity(start, new TdApi.TextEntityTypePreCode("latex"));
        }
        break;
      }
      case TdApi.PageBlockThinking.CONSTRUCTOR: {
        TdApi.PageBlockThinking thinking = (TdApi.PageBlockThinking) block;
        if (isEmpty(thinking.text)) {
          break;
        }
        blockSeparator();
        appendQuoted(() -> {
          int start = text.length();
          text.append(Lang.getString(R.string.RichMessageThinking));
          addFormattingEntity(start, new TdApi.TextEntityTypeBold());
          text.append('\n');
          start = text.length();
          appendText(thinking.text);
          addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
        }, true);
        break;
      }
      case TdApi.PageBlockDivider.CONSTRUCTOR: {
        blockSeparator();
        text.append("――――――――");
        break;
      }
      case TdApi.PageBlockAnchor.CONSTRUCTOR:
      case TdApi.PageBlockUnsupported.CONSTRUCTOR:
        break;
      case TdApi.PageBlockList.CONSTRUCTOR: {
        if (depth == 0) {
          blockSeparator();
        } else {
          newLine();
        }
        appendList((TdApi.PageBlockList) block, depth);
        break;
      }
      case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
        blockSeparator();
        appendQuoted(() -> {
          appendBlocksCompact(quote.blocks, depth);
          appendCredit(quote.credit);
        }, false);
        break;
      }
      case TdApi.PageBlockExpandableBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockExpandableBlockQuote quote = (TdApi.PageBlockExpandableBlockQuote) block;
        blockSeparator();
        appendQuoted(() -> {
          appendText(quote.text);
          appendCredit(quote.credit);
        }, true);
        break;
      }
      case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
        TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
        blockSeparator();
        appendQuoted(() -> {
          int start = text.length();
          appendText(quote.text);
          addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
          appendCredit(quote.credit);
        }, false);
        break;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        appendStyledBlock(details.header, new TdApi.TextEntityTypeBold());
        newLine();
        appendQuoted(() -> appendBlocksCompact(details.blocks, depth), !details.isOpen);
        break;
      }
      case TdApi.PageBlockTable.CONSTRUCTOR:
        appendTable((TdApi.PageBlockTable) block);
        break;
      case TdApi.PageBlockRelatedArticles.CONSTRUCTOR: {
        TdApi.PageBlockRelatedArticles related = (TdApi.PageBlockRelatedArticles) block;
        appendStyledBlock(related.header, new TdApi.TextEntityTypeBold());
        for (TdApi.PageBlockRelatedArticle article : related.articles) {
          newLine();
          text.append("• ");
          int start = text.length();
          text.append(StringUtils.isEmpty(article.title) ? article.url : article.title);
          if (!StringUtils.isEmpty(article.url)) {
            addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl(article.url));
          }
        }
        break;
      }
      case TdApi.PageBlockButtonRow.CONSTRUCTOR: {
        TdApi.PageBlockButtonRow row = (TdApi.PageBlockButtonRow) block;
        blockSeparator();
        boolean first = true;
        for (TdApi.InlineButton button : row.buttons) {
          if (!first) {
            text.append("   ");
          }
          first = false;
          appendButton(button);
        }
        break;
      }
      case TdApi.PageBlockChatLink.CONSTRUCTOR: {
        TdApi.PageBlockChatLink chatLink = (TdApi.PageBlockChatLink) block;
        blockSeparator();
        int start = text.length();
        text.append(StringUtils.isEmpty(chatLink.title) ? "@" + chatLink.username : chatLink.title);
        if (!StringUtils.isEmpty(chatLink.username)) {
          addFormattingEntity(start, new TdApi.TextEntityTypeTextUrl("https://t.me/" + chatLink.username));
        }
        break;
      }
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        if (isDisplayedSeparately(block)) {
          appendCaption(((TdApi.PageBlockPhoto) block).caption);
          break;
        }
        appendMedia("🖼 " + Lang.getString(R.string.Photo), ((TdApi.PageBlockPhoto) block).caption);
        break;
      case TdApi.PageBlockVideo.CONSTRUCTOR:
        if (isDisplayedSeparately(block)) {
          appendCaption(((TdApi.PageBlockVideo) block).caption);
          break;
        }
        appendMedia("🎬 " + Lang.getString(R.string.Video), ((TdApi.PageBlockVideo) block).caption);
        break;
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        if (isDisplayedSeparately(block)) {
          appendCaption(((TdApi.PageBlockAnimation) block).caption);
          break;
        }
        appendMedia("🎬 " + Lang.getString(R.string.Gif), ((TdApi.PageBlockAnimation) block).caption);
        break;
      case TdApi.PageBlockAudio.CONSTRUCTOR: {
        TdApi.PageBlockAudio audio = (TdApi.PageBlockAudio) block;
        String label = audio.audio != null ? Lang.getString(R.string.ChatContentSong, TD.getTitle(audio.audio), TD.getSubtitle(audio.audio)) : Lang.getString(R.string.Audio);
        appendMedia("🎵 " + label, audio.caption);
        break;
      }
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR:
        appendMedia("🎤 " + Lang.getString(R.string.ChatContentVoice), ((TdApi.PageBlockVoiceNote) block).caption);
        break;
      case TdApi.PageBlockDocument.CONSTRUCTOR: {
        TdApi.PageBlockDocument document = (TdApi.PageBlockDocument) block;
        String label = document.document != null && !StringUtils.isEmpty(document.document.fileName) ? document.document.fileName : Lang.getString(R.string.File);
        appendMedia("📎 " + label, document.caption);
        break;
      }
      case TdApi.PageBlockMap.CONSTRUCTOR:
        appendMedia("📍 " + Lang.getString(R.string.Location), ((TdApi.PageBlockMap) block).caption);
        break;
      case TdApi.PageBlockEmbedded.CONSTRUCTOR: {
        TdApi.PageBlockEmbedded embedded = (TdApi.PageBlockEmbedded) block;
        if (!StringUtils.isEmpty(embedded.url)) {
          blockSeparator();
          int start = text.length();
          text.append(embedded.url);
          addFormattingEntity(start, new TdApi.TextEntityTypeUrl());
          appendCaption(embedded.caption);
        } else {
          appendCaption(embedded.caption);
        }
        break;
      }
      case TdApi.PageBlockEmbeddedPost.CONSTRUCTOR: {
        TdApi.PageBlockEmbeddedPost post = (TdApi.PageBlockEmbeddedPost) block;
        blockSeparator();
        appendQuoted(() -> {
          if (!StringUtils.isEmpty(post.author)) {
            int start = text.length();
            text.append(post.author);
            addFormattingEntity(start, new TdApi.TextEntityTypeBold());
          }
          appendBlocksCompact(post.blocks, depth);
          appendCaption(post.caption);
        }, false);
        break;
      }
      case TdApi.PageBlockCollage.CONSTRUCTOR: {
        TdApi.PageBlockCollage collage = (TdApi.PageBlockCollage) block;
        appendBlocks(collage.blocks, depth);
        appendCaption(collage.caption);
        break;
      }
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
        TdApi.PageBlockSlideshow slideshow = (TdApi.PageBlockSlideshow) block;
        appendBlocks(slideshow.blocks, depth);
        appendCaption(slideshow.caption);
        break;
      }
      case TdApi.PageBlockCover.CONSTRUCTOR:
        appendBlock(((TdApi.PageBlockCover) block).cover, depth);
        break;
      default:
        // Unknown block type, ignore it
        break;
    }
  }

  private void appendCredit (@Nullable TdApi.RichText credit) {
    if (!isEmpty(credit)) {
      newLine();
      int start = text.length();
      text.append("— ");
      appendText(credit);
      addFormattingEntity(start, new TdApi.TextEntityTypeItalic());
    }
  }

  /**
   * Appends blocks separated by a single line break (used inside quotes and list items).
   */
  private void appendBlocksCompact (@Nullable TdApi.PageBlock[] blocks, int depth) {
    if (blocks == null) {
      return;
    }
    int quoteStart = text.length();
    for (TdApi.PageBlock block : blocks) {
      int before = text.length();
      appendBlock(block, depth);
      // Replace double separator added by a nested block with a single line break
      if (before > quoteStart && text.length() > before + 1 && text.charAt(before) == '\n' && text.charAt(before + 1) == '\n') {
        text.deleteCharAt(before);
        shiftEntities(before, -1);
      }
    }
  }

  private void shiftEntities (int from, int delta) {
    for (TdApi.TextEntity entity : entities) {
      if (entity.offset >= from) {
        entity.offset += delta;
      } else if (entity.offset + entity.length > from) {
        entity.length += delta;
      }
    }
  }

  private void appendList (TdApi.PageBlockList list, int depth) {
    StringBuilder indent = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      indent.append("    ");
    }
    boolean first = true;
    for (TdApi.PageBlockListItem item : list.items) {
      if (!first) {
        newLine();
      }
      first = false;
      text.append(indent);
      String label;
      if (item.hasCheckbox) {
        label = item.isChecked ? "☑" : "☐";
      } else if (!StringUtils.isEmpty(item.label)) {
        label = item.label;
      } else if (item.value != 0) {
        label = formatListValue(item.value, item.type) + ".";
      } else {
        label = depth % 2 == 0 ? "•" : "◦";
      }
      text.append(label).append(' ');
      appendListItemBlocks(item.blocks, depth, indent.toString());
    }
  }

  private void appendListItemBlocks (@Nullable TdApi.PageBlock[] blocks, int depth, String indent) {
    if (blocks == null) {
      return;
    }
    boolean first = true;
    for (TdApi.PageBlock block : blocks) {
      if (block.getConstructor() == TdApi.PageBlockList.CONSTRUCTOR) {
        newLine();
        appendList((TdApi.PageBlockList) block, depth + 1);
      } else if (first && block.getConstructor() == TdApi.PageBlockParagraph.CONSTRUCTOR) {
        appendText(((TdApi.PageBlockParagraph) block).text);
      } else {
        int before = text.length();
        appendBlock(block, depth + 1);
        if (text.length() > before + 1 && text.charAt(before) == '\n' && text.charAt(before + 1) == '\n') {
          text.deleteCharAt(before);
          shiftEntities(before, -1);
        }
        if (first && before > 0 && text.length() > before && text.charAt(before) == '\n') {
          // First block of an item must stay on the same line as its label
          text.deleteCharAt(before);
          shiftEntities(before, -1);
        }
      }
      first = false;
    }
  }

  private static String formatListValue (int value, @Nullable String type) {
    if (type == null) {
      return Integer.toString(value);
    }
    switch (type) {
      case "a":
        return toLetters(value, 'a');
      case "A":
        return toLetters(value, 'A');
      case "i":
        return toRoman(value).toLowerCase();
      case "I":
        return toRoman(value);
      default:
        return Integer.toString(value);
    }
  }

  private static String toLetters (int value, char base) {
    if (value <= 0) {
      return Integer.toString(value);
    }
    StringBuilder b = new StringBuilder();
    while (value > 0) {
      value--;
      b.insert(0, (char) (base + value % 26));
      value /= 26;
    }
    return b.toString();
  }

  private static String toRoman (int value) {
    if (value <= 0 || value >= 4000) {
      return Integer.toString(value);
    }
    final int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    final String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      while (value >= values[i]) {
        value -= values[i];
        b.append(symbols[i]);
      }
    }
    return b.toString();
  }

  private void appendTable (TdApi.PageBlockTable table) {
    if (table.cells == null || table.cells.length == 0) {
      appendStyledBlock(table.caption, new TdApi.TextEntityTypeBold());
      return;
    }
    appendStyledBlock(table.caption, new TdApi.TextEntityTypeBold());

    int columnCount = 0;
    for (TdApi.PageBlockTableCell[] row : table.cells) {
      int count = 0;
      for (TdApi.PageBlockTableCell cell : row) {
        count += Math.max(1, cell.colspan);
      }
      columnCount = Math.max(columnCount, count);
    }
    String[][] values = new String[table.cells.length][];
    int[] widths = new int[columnCount];
    for (int rowIndex = 0; rowIndex < table.cells.length; rowIndex++) {
      TdApi.PageBlockTableCell[] row = table.cells[rowIndex];
      values[rowIndex] = new String[row.length];
      int column = 0;
      for (int cellIndex = 0; cellIndex < row.length; cellIndex++) {
        String value = plainTextOf(row[cellIndex].text).replace('\n', ' ').trim();
        values[rowIndex][cellIndex] = value;
        if (Math.max(1, row[cellIndex].colspan) == 1 && column < columnCount) {
          widths[column] = Math.max(widths[column], value.codePointCount(0, value.length()));
        }
        column += Math.max(1, row[cellIndex].colspan);
      }
    }
    int totalWidth = 0;
    for (int width : widths) {
      totalWidth += width;
    }
    totalWidth += Math.max(0, columnCount - 1) * 3;

    blockSeparator();
    if (totalWidth <= MAX_MONOSPACE_TABLE_WIDTH) {
      // Small tables are rendered as aligned monospace text
      int start = text.length();
      for (int rowIndex = 0; rowIndex < table.cells.length; rowIndex++) {
        if (rowIndex > 0) {
          text.append('\n');
        }
        TdApi.PageBlockTableCell[] row = table.cells[rowIndex];
        int column = 0;
        boolean isHeader = true;
        for (int cellIndex = 0; cellIndex < row.length; cellIndex++) {
          if (cellIndex > 0) {
            text.append(" │ ");
          }
          int span = Math.max(1, row[cellIndex].colspan);
          int width = 0;
          for (int i = column; i < Math.min(columnCount, column + span); i++) {
            width += widths[i];
          }
          width += (span - 1) * 3;
          String value = values[rowIndex][cellIndex];
          text.append(value);
          for (int pad = value.codePointCount(0, value.length()); pad < width; pad++) {
            text.append(' ');
          }
          isHeader = isHeader && row[cellIndex].isHeader;
          column += span;
        }
        // Trailing spaces are useless
        while (text.length() > start && text.charAt(text.length() - 1) == ' ') {
          text.setLength(text.length() - 1);
        }
        if (isHeader && rowIndex == 0 && table.cells.length > 1) {
          text.append('\n');
          for (int i = 0; i < totalWidth; i++) {
            text.append('─');
          }
        }
      }
      if (preDepth == 0) {
        addEntity(start, new TdApi.TextEntityTypePre());
      }
    } else {
      for (int rowIndex = 0; rowIndex < table.cells.length; rowIndex++) {
        if (rowIndex > 0) {
          text.append('\n');
        }
        TdApi.PageBlockTableCell[] row = table.cells[rowIndex];
        for (int cellIndex = 0; cellIndex < row.length; cellIndex++) {
          if (cellIndex > 0) {
            text.append(" │ ");
          }
          int start = text.length();
          appendText(row[cellIndex].text);
          if (row[cellIndex].isHeader) {
            addFormattingEntity(start, new TdApi.TextEntityTypeBold());
          }
        }
      }
    }
  }
}
