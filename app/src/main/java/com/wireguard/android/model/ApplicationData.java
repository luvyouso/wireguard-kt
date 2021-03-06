/*
 * Copyright © 2018 Eric Kuck <eric@bluelinelabs.com>.
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.model;

import android.graphics.drawable.Drawable;

import com.wireguard.android.BR;
import com.wireguard.util.Keyed;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;

public class ApplicationData extends BaseObservable implements Keyed<String> {

    private final Drawable icon;
    private final String name;
    private final String packageName;
    private boolean excludedFromTunnel;

    public ApplicationData(final Drawable icon, final String name, final String packageName, final boolean excludedFromTunnel) {
        this.icon = icon;
        this.name = name;
        this.packageName = packageName;
        this.excludedFromTunnel = excludedFromTunnel;
    }

    public Drawable getIcon() {
        return icon;
    }

    public String getName() {
        return name;
    }

    public String getPackageName() {
        return packageName;
    }

    @Bindable
    public boolean isExcludedFromTunnel() {
        return excludedFromTunnel;
    }

    public void setExcludedFromTunnel(final boolean excludedFromTunnel) {
        this.excludedFromTunnel = excludedFromTunnel;
        notifyPropertyChanged(BR.excludedFromTunnel);
    }

    @Override
    public String getKey() {
        return name;
    }
}
