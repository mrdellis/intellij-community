// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij;

import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.util.text.OrdinalFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * @author yole
 */
public abstract class BundleBase {
  public static final char MNEMONIC = 0x1B;
  public static final String MNEMONIC_STRING = Character.toString(MNEMONIC);
  private static final String L10N_MARKER = "🔅";

  private static boolean assertOnMissedKeys = false;

  public static void assertOnMissedKeys(boolean doAssert) {
    assertOnMissedKeys = doAssert;
  }

  @NotNull
  public static String message(@NotNull ResourceBundle bundle, @NotNull String key, @NotNull Object... params) {
    return messageOrDefault(bundle, key, null, params);
  }

  private static final boolean myShowLocalizedMessages = Boolean.getBoolean("idea.l10n");

  public static String messageOrDefault(@Nullable ResourceBundle bundle,
                                        @NotNull String key,
                                        @Nullable String defaultValue,
                                        @NotNull Object... params) {
    if (bundle == null) return defaultValue;

    String value;
    try {
      value = bundle.getString(key);
    }
    catch (MissingResourceException e) {
      value = useDefaultValue(bundle, key, defaultValue);
    }

    String result = postprocessValue(bundle, value, params);

    if (myShowLocalizedMessages) {
      return result + L10N_MARKER;
    }
    return result;
  }

  @NotNull
  static String useDefaultValue(@Nullable ResourceBundle bundle, @NotNull String key, @Nullable String defaultValue) {
    if (defaultValue != null) {
      return defaultValue;
    }

    if (assertOnMissedKeys) {
      assert false : "'" + key + "' is not found in " + bundle;
    }
    return "!" + key + "!";
  }

  @Nullable
  static String postprocessValue(@NotNull ResourceBundle bundle, String value, @NotNull Object[] params) {
    value = replaceMnemonicAmpersand(value);

    if (params.length > 0 && value.indexOf('{') >= 0) {
      Locale locale = bundle.getLocale();
      try {
        MessageFormat format = locale != null ? new MessageFormat(value, locale) : new MessageFormat(value);
        OrdinalFormat.apply(format);
        value = format.format(params);
      }
      catch (IllegalArgumentException e) {
        value = "!invalid format: `" + value + "`!";
      }
    }

    return value;
  }

  @NotNull
  public static String format(@NotNull String value, @NotNull Object... params) {
    return params.length > 0 && value.indexOf('{') >= 0 ? MessageFormat.format(value, params) : value;
  }

  public static String replaceMnemonicAmpersand(@Nullable String value) {
    if (value == null || value.indexOf('&') < 0) {
      return value;
    }

    StringBuilder builder = new StringBuilder();
    boolean macMnemonic = value.contains("&&");
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c == '\\') {
        if (i < value.length() - 1 && value.charAt(i + 1) == '&') {
          builder.append('&');
          i++;
        }
        else {
          builder.append(c);
        }
      }
      else if (c == '&') {
        if (i < value.length() - 1 && value.charAt(i + 1) == '&') {
          if (SystemInfoRt.isMac) {
            builder.append(MNEMONIC);
          }
          i++;
        }
        else if (!SystemInfoRt.isMac || !macMnemonic) {
          builder.append(MNEMONIC);
        }
      }
      else {
        builder.append(c);
      }
      i++;
    }
    return builder.toString();
  }
}