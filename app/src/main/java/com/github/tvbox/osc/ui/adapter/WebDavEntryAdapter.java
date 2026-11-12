package com.github.tvbox.osc.ui.adapter;

import android.text.format.Formatter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.webdav.WebDavEntry;

import java.util.ArrayList;

public final class WebDavEntryAdapter extends BaseQuickAdapter<WebDavEntry, BaseViewHolder> {
    public WebDavEntryAdapter() {
        super(R.layout.item_webdav_entry, new ArrayList<WebDavEntry>());
    }

    @Override
    protected void convert(BaseViewHolder helper, WebDavEntry item) {
        helper.setText(R.id.tvEntryName, item.name);
        helper.setImageResource(R.id.ivEntryIcon,
                item.directory ? R.drawable.icon_folder : R.drawable.icon_video);
        helper.setText(R.id.tvEntryDetail, item.directory ? "文件夹" : Formatter.formatFileSize(
                helper.itemView.getContext(), Math.max(0L, item.size)));
    }
}
