/*
 * Copyright © 2018 Samuel Holland <samuel@sholland.org>
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.widget;

import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import com.wireguard.android.model.Tunnel;

import androidx.annotation.Nullable;

/**
 * InputFilter for entering WireGuard configuration names (Linux interface names).
 */

public class NameInputFilter implements InputFilter {
    private static boolean isAllowed(final char c) {
        return Character.isLetterOrDigit(c) || "_=+.-".indexOf(c) >= 0;
    }

    public static InputFilter newInstance() {
        return new NameInputFilter();
    }

    @Override @Nullable
    public CharSequence filter(final CharSequence source,
                               final int sStart, final int sEnd,
                               final Spanned dest,
                               final int dStart, final int dEnd) {
        SpannableStringBuilder replacement = null;
        int rIndex = 0;
        final int dLength = dest.length();
        for (int sIndex = sStart; sIndex < sEnd; ++sIndex) {
            final char c = source.charAt(sIndex);
            final int dIndex = dStart + (sIndex - sStart);
            // Restrict characters to those valid in interfaces.
            // Ensure adding this character does not push the length over the limit.
            if ((dIndex < Tunnel.NAME_MAX_LENGTH && isAllowed(c)) &&
                    dLength + (sIndex - sStart) < Tunnel.NAME_MAX_LENGTH) {
                ++rIndex;
            } else {
                if (replacement == null)
                    replacement = new SpannableStringBuilder(source, sStart, sEnd);
                replacement.delete(rIndex, rIndex + 1);
            }
        }
        return replacement;
    }
}
