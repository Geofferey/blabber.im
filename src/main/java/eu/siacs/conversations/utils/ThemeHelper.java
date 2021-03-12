/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.SettingsActivity;
import eu.siacs.conversations.ui.util.StyledAttributes;

public class ThemeHelper {

    public static int find(final Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final boolean auto = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("auto");
        final boolean black = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("black");
        boolean dark;
        if (auto) {
            dark = nightMode(context);
        } else {
            dark = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("dark") || black;
        }
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
        switch (themeColor) {
            case "blue":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Black_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark_Medium;
                        } else {
                            return R.style.ConversationsTheme_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Black_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark_Large;
                        } else {
                            return R.style.ConversationsTheme_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Black;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark;
                        } else {
                            return R.style.ConversationsTheme;
                        }
                }
            case "blabber":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark_Medium;
                        } else {
                            return R.style.ConversationsTheme_Blabber_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark_Large;
                        } else {
                            return R.style.ConversationsTheme_Blabber_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark;
                        } else {
                            return R.style.ConversationsTheme_Blabber;
                        }
                }
            case "orange":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark_Medium;
                        } else {
                            return R.style.ConversationsTheme_Orange_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark_Large;
                        } else {
                            return R.style.ConversationsTheme_Orange_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark;
                        } else {
                            return R.style.ConversationsTheme_Orange;
                        }
                }
            case "grey":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark_Medium;
                        } else {
                            return R.style.ConversationsTheme_Grey_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark_Large;
                        } else {
                            return R.style.ConversationsTheme_Grey_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark;
                        } else {
                            return R.style.ConversationsTheme_Grey;
                        }
                }
            case "pink":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark_Medium;
                        } else {
                            return R.style.ConversationsTheme_Pink_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark_Large;
                        } else {
                            return R.style.ConversationsTheme_Pink_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark;
                        } else {
                            return R.style.ConversationsTheme_Pink;
                        }
                }
            default:
                if (black) {
                    return R.style.ConversationsTheme_Blabber_Black;
                } else if (dark) {
                    return R.style.ConversationsTheme_Blabber_Dark;
                } else {
                    return R.style.ConversationsTheme_Blabber;
                }
        }
    }

    private static boolean nightMode(Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (nightModeFlags) {
            case Configuration.UI_MODE_NIGHT_YES:
                return true;
            case Configuration.UI_MODE_NIGHT_NO:
                return false;
            case Configuration.UI_MODE_NIGHT_UNDEFINED:
                return false;
            default:
                return false;
        }
    }

    public static int findDialog(Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final boolean black = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("black");
        final boolean auto = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("auto");
        boolean dark;
        if (auto) {
            dark = nightMode(context);
        } else {
            dark = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("dark") || black;
        }
        final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        switch (themeColor) {
            case "blue":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Black_Dialog_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark_Dialog_Medium;
                        } else {
                            return R.style.ConversationsTheme_Dialog_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Black_Dialog_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark_Dialog_Large;
                        } else {
                            return R.style.ConversationsTheme_Dialog_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Black_Dialog;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Dark_Dialog;
                        } else {
                            return R.style.ConversationsTheme_Dialog;
                        }
                }
            case "blabber":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black_Dialog_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark_Dialog_Medium;
                        } else {
                            return R.style.ConversationsTheme_Blabber_Dialog_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black_Dialog_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark_Dialog_Large;
                        } else {
                            return R.style.ConversationsTheme_Blabber_Dialog_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Blabber_Black_Dialog;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Blabber_Dark_Dialog;
                        } else {
                            return R.style.ConversationsTheme_Blabber_Dialog;
                        }
                }
            case "orange":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black_Dialog_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark_Dialog_Medium;
                        } else {
                            return R.style.ConversationsTheme_Orange_Dialog_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black_Dialog_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark_Dialog_Large;
                        } else {
                            return R.style.ConversationsTheme_Orange_Dialog_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Orange_Black_Dialog;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Orange_Dark_Dialog;
                        } else {
                            return R.style.ConversationsTheme_Orange_Dialog;
                        }
                }
            case "grey":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black_Dialog_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark_Dialog_Medium;
                        } else {
                            return R.style.ConversationsTheme_Grey_Dialog_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black_Dialog_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark_Dialog_Large;
                        } else {
                            return R.style.ConversationsTheme_Grey_Dialog_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Grey_Black_Dialog;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Grey_Dark_Dialog;
                        } else {
                            return R.style.ConversationsTheme_Grey_Dialog;
                        }
                }
            case "pink":
                switch (fontSize) {
                    case "medium":
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black_Dialog_Medium;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark_Dialog_Medium;
                        } else {
                            return R.style.ConversationsTheme_Pink_Dialog_Medium;
                        }
                    case "large":
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black_Dialog_Large;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark_Dialog_Large;
                        } else {
                            return R.style.ConversationsTheme_Pink_Dialog_Large;
                        }
                    default:
                        if (black) {
                            return R.style.ConversationsTheme_Pink_Black_Dialog;
                        } else if (dark) {
                            return R.style.ConversationsTheme_Pink_Dark_Dialog;
                        } else {
                            return R.style.ConversationsTheme_Pink_Dialog;
                        }
                }
            default:
                if (black) {
                    return R.style.ConversationsTheme_Blabber_Black_Dialog;
                } else if (dark) {
                    return R.style.ConversationsTheme_Blabber_Dark_Dialog;
                } else {
                    return R.style.ConversationsTheme_Blabber_Dialog;
                }
        }
    }

    public static boolean isDark(@StyleRes int id) {
        switch (id) {
            //blue
            case R.style.ConversationsTheme_Dark:
            case R.style.ConversationsTheme_Dark_Large:
            case R.style.ConversationsTheme_Dark_Medium:
            case R.style.ConversationsTheme_Black:
            case R.style.ConversationsTheme_Black_Large:
            case R.style.ConversationsTheme_Black_Medium:
                //blabber
            case R.style.ConversationsTheme_Blabber_Dark:
            case R.style.ConversationsTheme_Blabber_Dark_Large:
            case R.style.ConversationsTheme_Blabber_Dark_Medium:
            case R.style.ConversationsTheme_Blabber_Black:
            case R.style.ConversationsTheme_Blabber_Black_Large:
            case R.style.ConversationsTheme_Blabber_Black_Medium:
                //orange
            case R.style.ConversationsTheme_Orange_Dark:
            case R.style.ConversationsTheme_Orange_Dark_Large:
            case R.style.ConversationsTheme_Orange_Dark_Medium:
            case R.style.ConversationsTheme_Orange_Black:
            case R.style.ConversationsTheme_Orange_Black_Large:
            case R.style.ConversationsTheme_Orange_Black_Medium:
                //grey
            case R.style.ConversationsTheme_Grey_Dark:
            case R.style.ConversationsTheme_Grey_Dark_Large:
            case R.style.ConversationsTheme_Grey_Dark_Medium:
            case R.style.ConversationsTheme_Grey_Black:
            case R.style.ConversationsTheme_Grey_Black_Large:
            case R.style.ConversationsTheme_Grey_Black_Medium:
                //pink
            case R.style.ConversationsTheme_Pink_Dark:
            case R.style.ConversationsTheme_Pink_Dark_Large:
            case R.style.ConversationsTheme_Pink_Dark_Medium:
            case R.style.ConversationsTheme_Pink_Black:
            case R.style.ConversationsTheme_Pink_Black_Large:
            case R.style.ConversationsTheme_Pink_Black_Medium:
                return true;
            default:
                return false;
        }
    }

    public static ColorStateList AudioPlayerColor(Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final boolean auto = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("auto");
        boolean dark;
        if (auto) {
            dark = nightMode(context);
        } else {
            dark = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("dark");
        }
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        switch (themeColor) {
            case "blue":
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkblue);
            case "blabber":
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkblabber);
            case "orange":
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkorange);
            case "grey":
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkgrey);
            case "pink":
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkpink);
            default:
                return dark ? ContextCompat.getColorStateList(context, R.color.white70) : ContextCompat.getColorStateList(context, R.color.darkblabber);
        }
    }

    public SharedPreferences getPreferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static int notificationColor(Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        switch (themeColor) {
            case "blue":
                return R.color.primary;
            case "blabber":
                return R.color.primary_dark_blabber;
            case "orange":
                return R.color.primary_orange;
            case "grey":
                return R.color.primary_grey;
            case "pink":
                return R.color.primary_pink;
            default:
                return R.color.primary_dark_blabber;
        }
    }

    public static int getMessageTextColor(Context context, boolean onDark, boolean primary) {
        if (onDark) {
            return ContextCompat.getColor(context, primary ? R.color.white : R.color.white70);
        } else {
            return ContextCompat.getColor(context, primary ? R.color.black87 : R.color.black54);
        }
    }

    public static int messageTextColor(Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final boolean auto = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("auto");
        boolean dark;
        if (auto) {
            dark = nightMode(context);
        } else {
            dark = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("dark") || sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme)).equals("black");
        }
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        switch (themeColor) {
            case "blue":
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkblue);
            case "blabber":
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkblabber);
            case "orange":
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkorange);
            case "grey":
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkgrey);
            case "pink":
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkpink);
            default:
                return dark ? getMessageTextColor(context, dark, false) : ContextCompat.getColor(context, R.color.darkblabber);
        }
    }

    public static int getMessageTextColorPrivate(Context context) {
        return StyledAttributes.getColor(context, R.attr.colorAccent);
    }

    public static int getWarningTextColor(Context context, boolean onDark) {
        if (onDark) {
            return ContextCompat.getColor(context, R.color.white70);
        } else {
            return ContextCompat.getColor(context, R.color.black26);
        }
    }

    public static void fix(Snackbar snackbar) {
        final Context context = snackbar.getContext();
        TypedArray typedArray = context.obtainStyledAttributes(new int[]{R.attr.TextSizeBody1});
        final float size = typedArray.getDimension(0, 0f);
        typedArray.recycle();
        if (size != 0f) {
            final TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            final TextView action = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
            if (text != null && action != null) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                action.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                action.setTextColor(ContextCompat.getColor(context, R.color.deep_purple_a100));
            }
        }
    }

    public static boolean showColoredUsernameBackGround(Context context, boolean dark) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final Resources resources = context.getResources();
        final String themeColor = sharedPreferences.getString("theme_color", resources.getString(R.string.theme_color));
        switch (themeColor) {
            case "blue":
                return dark ? false : false;
            case "blabber":
                return dark ? true : false;
            case "orange":
                return dark ? true : false;
            case "grey":
                return dark ? false : false;
            case "pink":
                return dark ? true : false;
            default:
                return dark ? true : false;
        }
    }
}