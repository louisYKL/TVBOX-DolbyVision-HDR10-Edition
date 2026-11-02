package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ParseBean;

import java.util.ArrayList;

public class ParseAdapter extends BaseQuickAdapter<ParseBean, BaseViewHolder> {
    public ParseAdapter() {
        super(R.layout.item_play_parse, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, ParseBean item) {
        TextView tvParse = helper.getView(R.id.tvParse);
        tvParse.setVisibility(View.VISIBLE);
        // button_dialog_main is dark when unfocused and light only when focused.
        // Keep the default parser highlighted without using dark text on the dark state.
        tvParse.setTextColor(ContextCompat.getColorStateList(mContext,
                item.isDefault() ? R.color.apple_tv_button_text_selected : R.color.apple_tv_button_text_light_focus));
        tvParse.setText(item.getName());
        if (helper.getLayoutPosition() == 0) {
            helper.itemView.setNextFocusLeftId(R.id.screen_display);
        }
    }
}
