package org.thoughtcrime.securesms.payments.preferences.lightning;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningNodeType;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor;
import org.thoughtcrime.securesms.util.ViewUtil;

/**
 * Fragment for configuring Lightning node connection.
 * 
 * Supports connecting to Lightning nodes via:
 * - NWC (Nostr Wallet Connect) - simplest option, just paste a nostr+walletconnect:// URI
 * 
 * Future support planned for:
 * - LND (URL + macaroon)
 * - CLN (URL + rune)
 * - Phoenixd (URL + password)
 * - Strike/Blink/Speed (API key)
 * 
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
public class LightningConfigFragment extends Fragment {

    private static final String TAG = Log.tag(LightningConfigFragment.class);

    private EditText nwcUriInput;
    private Button connectButton;
    private Button disconnectButton;
    private ProgressBar spinner;
    private TextView statusText;

    public LightningConfigFragment() {
        super(R.layout.lightning_config_fragment);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.lightning_config_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Toolbar toolbar = view.findViewById(R.id.lightning_config_toolbar);
        nwcUriInput = view.findViewById(R.id.lightning_nwc_uri);
        connectButton = view.findViewById(R.id.lightning_connect_button);
        disconnectButton = view.findViewById(R.id.lightning_disconnect_button);
        spinner = view.findViewById(R.id.lightning_spinner);
        statusText = view.findViewById(R.id.lightning_status);

        toolbar.setNavigationOnClickListener(v -> {
            ViewUtil.hideKeyboard(requireContext(), v);
            Navigation.findNavController(v).popBackStack();
        });

        connectButton.setOnClickListener(v -> connectNwc());
        disconnectButton.setOnClickListener(v -> disconnect());

        updateUiState();
    }

    private void updateUiState() {
        boolean isConfigured = LightningUiInteractor.isConfigured(AppDependencies.getApplication());
        LightningNodeType nodeType = LightningUiInteractor.getConfiguredNodeType(AppDependencies.getApplication());
        
        if (isConfigured && nodeType != null) {
            statusText.setText(getString(R.string.LightningConfig__connected_to, nodeType.name()));
            statusText.setVisibility(View.VISIBLE);
            nwcUriInput.setVisibility(View.GONE);
            connectButton.setVisibility(View.GONE);
            disconnectButton.setVisibility(View.VISIBLE);
        } else {
            statusText.setText(R.string.LightningConfig__not_connected);
            statusText.setVisibility(View.VISIBLE);
            nwcUriInput.setVisibility(View.VISIBLE);
            connectButton.setVisibility(View.VISIBLE);
            disconnectButton.setVisibility(View.GONE);
        }
    }

    private void connectNwc() {
        String uri = nwcUriInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(uri)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_nwc_uri, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!uri.startsWith("nostr+walletconnect://") && !uri.startsWith("nostr://")) {
            Toast.makeText(requireContext(), R.string.LightningConfig__invalid_nwc_uri, Toast.LENGTH_LONG).show();
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureNwc(AppDependencies.getApplication(), uri);
                
                if (success) {
                    // Test the connection
                    boolean available = LightningUiInteractor.isAvailableBlocking(AppDependencies.getApplication());
                    
                    requireView().post(() -> {
                        setLoading(false);
                        if (available) {
                            SignalStore.payments().setLightningEnabled(true);
                            Toast.makeText(requireContext(), R.string.LightningConfig__connected_successfully, Toast.LENGTH_SHORT).show();
                            updateUiState();
                        } else {
                            Toast.makeText(requireContext(), R.string.LightningConfig__connection_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    requireView().post(() -> {
                        setLoading(false);
                        Toast.makeText(requireContext(), R.string.LightningConfig__configuration_failed, Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure NWC", t);
                requireView().post(() -> {
                    setLoading(false);
                    Toast.makeText(requireContext(), R.string.LightningConfig__configuration_failed, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void disconnect() {
        setLoading(true);

        new Thread(() -> {
            try {
                LightningUiInteractor.clearConfiguration(AppDependencies.getApplication());
                SignalStore.payments().setLightningEnabled(false);
                
                requireView().post(() -> {
                    setLoading(false);
                    Toast.makeText(requireContext(), R.string.LightningConfig__disconnected, Toast.LENGTH_SHORT).show();
                    updateUiState();
                });
            } catch (Throwable t) {
                Log.w(TAG, "Failed to disconnect Lightning", t);
                requireView().post(() -> {
                    setLoading(false);
                    Toast.makeText(requireContext(), R.string.LightningConfig__disconnect_failed, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!loading);
        disconnectButton.setEnabled(!loading);
        nwcUriInput.setEnabled(!loading);
    }
}
