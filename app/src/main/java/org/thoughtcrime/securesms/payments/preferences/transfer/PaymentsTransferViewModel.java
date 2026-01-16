package org.thoughtcrime.securesms.payments.preferences.transfer;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.payments.MobileCoinPublicAddress;

final class PaymentsTransferViewModel extends ViewModel {

  private final MutableLiveData<String> address = new MutableLiveData<>();
  private final MobileCoinPublicAddress ownAddress;

  PaymentsTransferViewModel() {
    // Only get MobileCoin address if not in Cashu mode
    if (!SignalStore.payments().cashuEnabled()) {
      ownAddress = AppDependencies.getPayments().getWallet().getMobileCoinPublicAddress();
    } else {
      ownAddress = null;
    }
  }

  LiveData<String> getAddress() {
    return address;
  }

  @Nullable
  MobileCoinPublicAddress getOwnAddress() {
    return ownAddress;
  }

  @AnyThread
  void postQrData(@NonNull String qrData) {
    address.postValue(qrData);
  }

  public static final class Factory implements ViewModelProvider.Factory {
    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection ConstantConditions
      return modelClass.cast(new PaymentsTransferViewModel());
    }
  }
}
