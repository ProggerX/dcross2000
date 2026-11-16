/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2015 (C) Scott Jackson
*/

package com.proggerx.dcross2000.view;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.proggerx.dcross2000.R;

public class BasicListView extends UpdateView<String> {
	private TextView titleView;
	private ImageView iconView;

	public BasicListView(Context context) {
		this(context, R.layout.basic_list_item);
	}

	public BasicListView(Context context, int layout) {
		super(context, false);
		LayoutInflater.from(context).inflate(layout, this, true);

		titleView = (TextView) findViewById(R.id.item_name);
		iconView = (ImageView) findViewById(R.id.item_icon);
		starButton = (ImageButton) findViewById(R.id.item_star);
		starButton.setFocusable(false);
		moreButton = (ImageView) findViewById(R.id.item_more);
		moreButton.setVisibility(View.GONE);
	}

	public void setIcon(int resId) {
		if(iconView == null) return;
		if(resId == 0) {
			iconView.setVisibility(View.GONE);
		} else {
			iconView.setImageResource(resId);
			iconView.setVisibility(View.VISIBLE);
		}
	}

	protected void setObjectImpl(String string) {
		titleView.setText(string);
	}
}
