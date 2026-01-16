package org.thoughtcrime.securesms.payments.preferences.viewholder;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.payments.preferences.PaymentsHomeAdapter;
import org.thoughtcrime.securesms.payments.preferences.model.LightningActivityItem;
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder;

/**
 * ViewHolder for displaying Lightning transaction activity items.
 */
public final class LightningActivityViewHolder extends MappingViewHolder<LightningActivityItem> {

  private final PaymentsHomeAdapter.Callbacks callbacks;

  public LightningActivityViewHolder(@NonNull View itemView, @NonNull PaymentsHomeAdapter.Callbacks callbacks) {
    super(itemView);
    this.callbacks = callbacks;
  }

  @Override
  public void bind(@NonNull LightningActivityItem model) {
    TextView title  = itemView.findViewById(R.id.lightning_activity_title);
    TextView date   = itemView.findViewById(R.id.lightning_activity_date);
    TextView amount = itemView.findViewById(R.id.lightning_activity_amount);
    AvatarImageView avatar = itemView.findViewById(R.id.lightning_activity_avatar);

    // Set Lightning bolt icon for avatar
    avatar.setImageResource(R.drawable.ic_lightning_bolt_24);

    title.setText(model.getTitle(itemView.getContext()));
    date.setText(model.getDate(itemView.getContext()));
    amount.setText(model.getAmountText());
    amount.setTextColor(itemView.getResources().getColor(model.getAmountColor()));

    // Set click listener to open details
    itemView.setOnClickListener(v -> callbacks.onLightningItem(model));
  }
}
