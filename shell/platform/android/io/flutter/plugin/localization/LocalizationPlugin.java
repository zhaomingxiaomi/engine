// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.localization;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.embedding.engine.systemchannels.LocalizationChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android implementation of the localization plugin. */
public class LocalizationPlugin {
  @NonNull private final LocalizationChannel localizationChannel;
  @NonNull private final Context context;

  @VisibleForTesting
  final LocalizationChannel.LocalizationMessageHandler localizationMessageHandler =
      new LocalizationChannel.LocalizationMessageHandler() {
        @Override
        public String getStringResource(@NonNull String key, @Nullable String localeString) {
          Context localContext = context;
          String stringToReturn = null;
          Locale savedLocale = null;

          if (localeString != null) {
            Locale locale = localeFromString(localeString);

            // setLocale and createConfigurationContext is only available on API >= 17
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
              Configuration config = new Configuration(context.getResources().getConfiguration());
              config.setLocale(locale);
              localContext = context.createConfigurationContext(config);
            } else {
              // In API < 17, we have to update the locale in Configuration.
              Resources resources = context.getResources();
              Configuration config = resources.getConfiguration();
              savedLocale = config.locale;
              config.locale = locale;
              resources.updateConfiguration(config, null);
            }
          }

          String packageName = context.getPackageName();
          int resId = localContext.getResources().getIdentifier(key, "string", packageName);
          if (resId != 0) {
            // 0 means the resource is not found.
            stringToReturn = localContext.getResources().getString(resId);
          }

          // In API < 17, we had to restore the original locale after using.
          if (localeString != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Resources resources = context.getResources();
            Configuration config = resources.getConfiguration();
            config.locale = savedLocale;
            resources.updateConfiguration(config, null);
          }

          return stringToReturn;
        }
      };

  public LocalizationPlugin(
      @NonNull Context context, @NonNull LocalizationChannel localizationChannel) {

    this.context = context;
    this.localizationChannel = localizationChannel;
    this.localizationChannel.setLocalizationMessageHandler(localizationMessageHandler);
  }

  /**
   * Computes the {@link Locale} in supportedLocales that best matches the user's preferred locales.
   *
   * <p>FlutterEngine must be non-null when this method is invoked.
   */
  @SuppressWarnings("deprecation")
  @Nullable
  public Locale resolveNativeLocale(@Nullable List<Locale> supportedLocales) {
    if (supportedLocales == null || supportedLocales.isEmpty()) {
      return null;
    }

    // Android improved the localization resolution algorithms after API 24 (7.0, Nougat).
    // See https://developer.android.com/guide/topics/resources/multilingual-support
    //
    // LanguageRange and Locale.lookup was added in API 26 and is the preferred way to
    // select a locale. Pre-API 26, we implement a manual locale resolution.
    if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      // Modern locale resolution using LanguageRange
      // https://developer.android.com/guide/topics/resources/multilingual-support#postN
      List<Locale.LanguageRange> languageRanges = new ArrayList<>();
      LocaleList localeList = context.getResources().getConfiguration().getLocales();
      int localeCount = localeList.size();
      for (int index = 0; index < localeCount; ++index) {
        Locale locale = localeList.get(index);
        // Convert locale string into language range format.
        String fullRange = locale.getLanguage();
        if (!locale.getScript().isEmpty()) {
          fullRange += "-" + locale.getScript();
        }
        if (!locale.getCountry().isEmpty()) {
          fullRange += "-" + locale.getCountry();
        }
        languageRanges.add(new Locale.LanguageRange(fullRange));
        languageRanges.add(new Locale.LanguageRange(locale.getLanguage()));
        languageRanges.add(new Locale.LanguageRange(locale.getLanguage() + "-*"));
      }
      Locale platformResolvedLocale = Locale.lookup(languageRanges, supportedLocales);
      if (platformResolvedLocale != null) {
        return platformResolvedLocale;
      }
      return supportedLocales.get(0);
    } else if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
      // Modern locale resolution without languageRange
      // https://developer.android.com/guide/topics/resources/multilingual-support#postN
      LocaleList localeList = context.getResources().getConfiguration().getLocales();
      for (int index = 0; index < localeList.size(); ++index) {
        Locale preferredLocale = localeList.get(index);
        // Look for exact match.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.equals(locale)) {
            return locale;
          }
        }
        // Look for exact language only match.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.toLanguageTag())) {
            return locale;
          }
        }
        // Look for any locale with matching language.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.getLanguage())) {
            return locale;
          }
        }
      }
      return supportedLocales.get(0);
    }

    // Legacy locale resolution
    // https://developer.android.com/guide/topics/resources/multilingual-support#preN
    Locale preferredLocale = context.getResources().getConfiguration().locale;
    if (preferredLocale != null) {
      // Look for exact match.
      for (Locale locale : supportedLocales) {
        if (preferredLocale.equals(locale)) {
          return locale;
        }
      }
      // Look for exact language only match.
      for (Locale locale : supportedLocales) {
        if (preferredLocale.getLanguage().equals(locale.toString())) {
          return locale;
        }
      }
    }
    return supportedLocales.get(0);
  }

  /**
   * Send the current {@link Locale} configuration to Flutter.
   *
   * <p>FlutterEngine must be non-null when this method is invoked.
   */
  @SuppressWarnings("deprecation")
  public void sendLocalesToFlutter(@NonNull Configuration config) {
    List<Locale> locales = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
      LocaleList localeList = config.getLocales();
      int localeCount = localeList.size();
      for (int index = 0; index < localeCount; ++index) {
        Locale locale = localeList.get(index);
        locales.add(locale);
      }
    } else {
      locales.add(config.locale);
    }

    localizationChannel.sendLocales(locales);
  }

  @VisibleForTesting
  @NonNull
  public static Locale localeFromString(@NonNull String localeString) {
    // Use Locale.forLanguageTag if available (API 21+).
    if (false && Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      return Locale.forLanguageTag(localeString);
    } else {
      // Normalize the locale string, replace all underscores with hyphens.
      localeString = localeString.replace('_', '-');

      // Pre-API 21, we fall back to manually parsing the locale tag.
      String parts[] = localeString.split("-", -1);

      // The format is:
      // language[-script][-region][-...]
      // where script is an alphabet string of length 4, and region is either an alphabet string of
      // length 2 or a digit string of length 3.

      // Assume the first part is always the language code.
      String languageCode = parts[0];
      String scriptCode = "";
      String countryCode = "";
      int index = 1;
      if (parts.length > index && parts[index].length() == 4) {
        scriptCode = parts[index];
        index++;
      }
      if (parts.length > index && parts[index].length() >= 2 && parts[index].length() <= 3) {
        countryCode = parts[index];
        index++;
      }
      // Ignore the rest of the locale for this purpose.
      return new Locale(languageCode, countryCode, scriptCode);
    }
  }
}
