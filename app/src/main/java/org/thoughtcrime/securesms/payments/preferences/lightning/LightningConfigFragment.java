package org.thoughtcrime.securesms.payments.preferences.lightning;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import org.thoughtcrime.securesms.payments.engine.lightning.LightningConfig;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningNodeType;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fragment for configuring Lightning node connection.
 * 
 * Supports connecting to Lightning nodes via:
 * - NWC (Nostr Wallet Connect) - simplest option, just paste a nostr+walletconnect:// URI
 * - LND - URL + macaroon
 * - CLN (Core Lightning) - URL + rune
 * - Phoenixd - URL + password
 * - Strike - API key
 * - Blink - API key
 * - Speed - API key
 * 
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
public class LightningConfigFragment extends Fragment {

    private static final String TAG = Log.tag(LightningConfigFragment.class);

    // Node type mapping
    private final Map<String, LightningNodeType> nodeTypeMap = new LinkedHashMap<>();
    
    // UI elements
    private AutoCompleteTextView nodeTypeSelector;
    private Button connectButton;
    private Button disconnectButton;
    private Button editButton;
    private Button testButton;
    private ProgressBar spinner;
    private TextView statusText;
    private LinearLayout balanceSection;
    private TextView balanceText;
    
    // Sections
    private LinearLayout nwcSection;
    private LinearLayout lndSection;
    private LinearLayout clnSection;
    private LinearLayout phoenixdSection;
    private LinearLayout strikeSection;
    private LinearLayout blinkSection;
    private LinearLayout speedSection;
    private LinearLayout sparkSection;
    
    // NWC inputs
    private EditText nwcUriInput;
    
    // LND inputs
    private EditText lndUrlInput;
    private EditText lndMacaroonInput;
    
    // CLN inputs
    private EditText clnUrlInput;
    private EditText clnRuneInput;
    
    // Phoenixd inputs
    private EditText phoenixdUrlInput;
    private EditText phoenixdPasswordInput;
    
    // Strike input
    private EditText strikeApiKeyInput;
    
    // Blink input
    private EditText blinkApiKeyInput;
    
    // Speed input
    private EditText speedApiKeyInput;
    
    // Spark inputs
    private EditText sparkMnemonicInput;
    private EditText sparkApiKeyInput;
    private Button sparkGenerateButton;
    private Button sparkAdvancedButton;
    private LinearLayout sparkAdvancedSection;
    
    private LightningNodeType selectedNodeType = LightningNodeType.NWC;

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
        
        initNodeTypeMap();

        Toolbar toolbar = view.findViewById(R.id.lightning_config_toolbar);
        nodeTypeSelector = view.findViewById(R.id.lightning_node_type);
        connectButton = view.findViewById(R.id.lightning_connect_button);
        disconnectButton = view.findViewById(R.id.lightning_disconnect_button);
        editButton = view.findViewById(R.id.lightning_edit_button);
        testButton = view.findViewById(R.id.lightning_test_button);
        spinner = view.findViewById(R.id.lightning_spinner);
        statusText = view.findViewById(R.id.lightning_status);
        balanceSection = view.findViewById(R.id.lightning_balance_section);
        balanceText = view.findViewById(R.id.lightning_balance_text);
        
        // Sections
        nwcSection = view.findViewById(R.id.lightning_nwc_section);
        lndSection = view.findViewById(R.id.lightning_lnd_section);
        clnSection = view.findViewById(R.id.lightning_cln_section);
        phoenixdSection = view.findViewById(R.id.lightning_phoenixd_section);
        strikeSection = view.findViewById(R.id.lightning_strike_section);
        blinkSection = view.findViewById(R.id.lightning_blink_section);
        speedSection = view.findViewById(R.id.lightning_speed_section);
        sparkSection = view.findViewById(R.id.lightning_spark_section);
        
        // NWC
        nwcUriInput = view.findViewById(R.id.lightning_nwc_uri);
        
        // LND
        lndUrlInput = view.findViewById(R.id.lightning_lnd_url);
        lndMacaroonInput = view.findViewById(R.id.lightning_lnd_macaroon);
        
        // CLN
        clnUrlInput = view.findViewById(R.id.lightning_cln_url);
        clnRuneInput = view.findViewById(R.id.lightning_cln_rune);
        
        // Phoenixd
        phoenixdUrlInput = view.findViewById(R.id.lightning_phoenixd_url);
        phoenixdPasswordInput = view.findViewById(R.id.lightning_phoenixd_password);
        
        // Strike
        strikeApiKeyInput = view.findViewById(R.id.lightning_strike_api_key);
        
        // Blink
        blinkApiKeyInput = view.findViewById(R.id.lightning_blink_api_key);
        
        // Speed
        speedApiKeyInput = view.findViewById(R.id.lightning_speed_api_key);
        
        // Spark
        sparkMnemonicInput = view.findViewById(R.id.lightning_spark_mnemonic);
        sparkApiKeyInput = view.findViewById(R.id.lightning_spark_api_key);
        sparkGenerateButton = view.findViewById(R.id.lightning_spark_generate_button);
        sparkAdvancedButton = view.findViewById(R.id.lightning_spark_advanced_button);
        sparkAdvancedSection = view.findViewById(R.id.lightning_spark_advanced_section);

        toolbar.setNavigationOnClickListener(v -> {
            ViewUtil.hideKeyboard(requireContext(), v);
            Navigation.findNavController(v).popBackStack();
        });

        setupNodeTypeSelector();
        
        connectButton.setOnClickListener(v -> connect());
        disconnectButton.setOnClickListener(v -> disconnect());
        editButton.setOnClickListener(v -> enterEditMode());
        testButton.setOnClickListener(v -> testConnection());
        sparkGenerateButton.setOnClickListener(v -> generateMnemonic());
        sparkAdvancedButton.setOnClickListener(v -> toggleAdvancedSettings());

        updateUiState();
    }
    
    private void initNodeTypeMap() {
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_nwc), LightningNodeType.NWC);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_lnd), LightningNodeType.LND);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_cln), LightningNodeType.CLN);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_phoenixd), LightningNodeType.PHOENIXD);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_strike), LightningNodeType.STRIKE);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_blink), LightningNodeType.BLINK);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_speed), LightningNodeType.SPEED);
        nodeTypeMap.put(getString(R.string.LightningConfig__node_type_spark), LightningNodeType.SPARK);
    }
    
    private void setupNodeTypeSelector() {
        String[] nodeTypes = nodeTypeMap.keySet().toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_dropdown_item_1line, nodeTypes);
        nodeTypeSelector.setAdapter(adapter);
        nodeTypeSelector.setText(nodeTypes[0], false);
        
        nodeTypeSelector.setOnItemClickListener((parent, v, position, id) -> {
            String selected = nodeTypes[position];
            selectedNodeType = nodeTypeMap.get(selected);
            showSectionForNodeType(selectedNodeType);
        });
    }
    
    private void showSectionForNodeType(LightningNodeType type) {
        // Hide all sections
        nwcSection.setVisibility(View.GONE);
        lndSection.setVisibility(View.GONE);
        clnSection.setVisibility(View.GONE);
        phoenixdSection.setVisibility(View.GONE);
        strikeSection.setVisibility(View.GONE);
        blinkSection.setVisibility(View.GONE);
        speedSection.setVisibility(View.GONE);
        sparkSection.setVisibility(View.GONE);
        
        // Show the selected section
        switch (type) {
            case NWC:
                nwcSection.setVisibility(View.VISIBLE);
                break;
            case LND:
                lndSection.setVisibility(View.VISIBLE);
                break;
            case CLN:
                clnSection.setVisibility(View.VISIBLE);
                break;
            case PHOENIXD:
                phoenixdSection.setVisibility(View.VISIBLE);
                break;
            case STRIKE:
                strikeSection.setVisibility(View.VISIBLE);
                break;
            case BLINK:
                blinkSection.setVisibility(View.VISIBLE);
                break;
            case SPEED:
                speedSection.setVisibility(View.VISIBLE);
                break;
            case SPARK:
                sparkSection.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void updateUiState() {
        // Use hasConfiguration to check if there's a saved config (ignores enabled flag)
        boolean hasConfig = LightningUiInteractor.hasConfiguration(AppDependencies.getApplication());
        boolean isEnabled = SignalStore.payments().lightningEnabled();
        LightningNodeType nodeType = LightningUiInteractor.getConfiguredNodeType(AppDependencies.getApplication());
        
        if (hasConfig && isEnabled && nodeType != null) {
            statusText.setText(getString(R.string.LightningConfig__connected_to, nodeType.name()));
            statusText.setVisibility(View.VISIBLE);
            
            // Hide config UI when connected
            requireView().findViewById(R.id.lightning_node_type_layout).setVisibility(View.GONE);
            nwcSection.setVisibility(View.GONE);
            lndSection.setVisibility(View.GONE);
            clnSection.setVisibility(View.GONE);
            phoenixdSection.setVisibility(View.GONE);
            strikeSection.setVisibility(View.GONE);
            blinkSection.setVisibility(View.GONE);
            speedSection.setVisibility(View.GONE);
            sparkSection.setVisibility(View.GONE);
            
            connectButton.setVisibility(View.GONE);
            disconnectButton.setVisibility(View.VISIBLE);
            editButton.setVisibility(View.VISIBLE);
            testButton.setVisibility(View.VISIBLE);
            balanceSection.setVisibility(View.VISIBLE);
            
            // Fetch and display balance in background
            fetchAndDisplayBalance();
        } else {
            statusText.setText(R.string.LightningConfig__not_connected);
            statusText.setVisibility(View.VISIBLE);
            
            // Show config UI when not connected
            requireView().findViewById(R.id.lightning_node_type_layout).setVisibility(View.VISIBLE);
            showSectionForNodeType(selectedNodeType);
            
            connectButton.setVisibility(View.VISIBLE);
            connectButton.setText(R.string.LightningConfig__connect);
            disconnectButton.setVisibility(View.GONE);
            editButton.setVisibility(View.GONE);
            testButton.setVisibility(View.GONE);
            balanceSection.setVisibility(View.GONE);
        }
    }
    
    private void fetchAndDisplayBalance() {
        new Thread(() -> {
            try {
                org.thoughtcrime.securesms.payments.engine.lightning.LightningBalance balance = 
                    LightningUiInteractor.getBalanceBlocking(AppDependencies.getApplication());
                if (balance != null) {
                    String balanceStr = String.format(java.util.Locale.getDefault(), 
                        "%s\n%s",
                        getString(R.string.LightningConfig__send_balance, formatSats(balance.getSendBalanceSats())),
                        getString(R.string.LightningConfig__receive_balance, formatSats(balance.getReceiveBalanceSats())));
                    requireView().post(() -> {
                        balanceText.setText(balanceStr);
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to fetch balance", t);
            }
        }).start();
    }
    
    private String formatSats(long sats) {
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(java.util.Locale.getDefault());
        nf.setGroupingUsed(true);
        return nf.format(sats);
    }
    
    private void testConnection() {
        Toast.makeText(requireContext(), R.string.LightningConfig__testing_connection, Toast.LENGTH_SHORT).show();
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean available = LightningUiInteractor.isAvailableBlocking(AppDependencies.getApplication());
                org.thoughtcrime.securesms.payments.engine.lightning.LightningBalance balance = 
                    LightningUiInteractor.getBalanceBlocking(AppDependencies.getApplication());
                
                requireView().post(() -> {
                    setLoading(false);
                    if (available) {
                        String message = getString(R.string.LightningConfig__connection_successful);
                        if (balance != null) {
                            message += "\n" + getString(R.string.LightningConfig__send_balance, formatSats(balance.getSendBalanceSats()));
                            message += "\n" + getString(R.string.LightningConfig__receive_balance, formatSats(balance.getReceiveBalanceSats()));
                        }
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.LightningConfig__test_connection)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                        
                        // Update balance display
                        if (balance != null) {
                            String balanceStr = String.format(java.util.Locale.getDefault(), 
                                "%s\n%s",
                                getString(R.string.LightningConfig__send_balance, formatSats(balance.getSendBalanceSats())),
                                getString(R.string.LightningConfig__receive_balance, formatSats(balance.getReceiveBalanceSats())));
                            balanceText.setText(balanceStr);
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.LightningConfig__connection_failed, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Test connection failed", t);
                requireView().post(() -> {
                    setLoading(false);
                    Toast.makeText(requireContext(), R.string.LightningConfig__connection_failed, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void connect() {
        switch (selectedNodeType) {
            case NWC:
                connectNwc();
                break;
            case LND:
                connectLnd();
                break;
            case CLN:
                connectCln();
                break;
            case PHOENIXD:
                connectPhoenixd();
                break;
            case STRIKE:
                connectStrike();
                break;
            case BLINK:
                connectBlink();
                break;
            case SPEED:
                connectSpeed();
                break;
            case SPARK:
                connectSpark();
                break;
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
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure NWC", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectLnd() {
        String url = lndUrlInput.getText().toString().trim();
        String macaroon = lndMacaroonInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_url, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(macaroon)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_macaroon, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureLnd(AppDependencies.getApplication(), url, macaroon);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure LND", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectCln() {
        String url = clnUrlInput.getText().toString().trim();
        String rune = clnRuneInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_url, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(rune)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_rune, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureCln(AppDependencies.getApplication(), url, rune);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure CLN", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectPhoenixd() {
        String url = phoenixdUrlInput.getText().toString().trim();
        String password = phoenixdPasswordInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_url, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(password)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_password, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configurePhoenixd(AppDependencies.getApplication(), url, password);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure Phoenixd", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectStrike() {
        String apiKey = strikeApiKeyInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_api_key, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureStrike(AppDependencies.getApplication(), apiKey);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure Strike", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectBlink() {
        String apiKey = blinkApiKeyInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_api_key, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureBlink(AppDependencies.getApplication(), apiKey);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure Blink", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void connectSpeed() {
        String apiKey = speedApiKeyInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_api_key, Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                boolean success = LightningUiInteractor.configureSpeed(AppDependencies.getApplication(), apiKey);
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure Speed", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void generateMnemonic() {
        try {
            // Generate 12-word mnemonic using LNI (via Kotlin helper to avoid name mangling issues)
            String mnemonic = LniHelper.generateMnemonic(null);
            sparkMnemonicInput.setText(mnemonic);
            Toast.makeText(requireContext(), R.string.LightningConfig__mnemonic_generated, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to generate mnemonic", t);
            Toast.makeText(requireContext(), R.string.LightningConfig__mnemonic_generation_failed, Toast.LENGTH_LONG).show();
        }
    }
    
    private void toggleAdvancedSettings() {
        if (sparkAdvancedSection.getVisibility() == View.VISIBLE) {
            sparkAdvancedSection.setVisibility(View.GONE);
            sparkAdvancedButton.setText(R.string.LightningConfig__advanced_settings);
        } else {
            sparkAdvancedSection.setVisibility(View.VISIBLE);
            sparkAdvancedButton.setText(R.string.LightningConfig__hide_advanced);
        }
    }
    
    private void connectSpark() {
        String mnemonic = sparkMnemonicInput.getText().toString().trim();
        String apiKey = sparkApiKeyInput.getText().toString().trim();
        
        if (TextUtils.isEmpty(mnemonic)) {
            Toast.makeText(requireContext(), R.string.LightningConfig__please_enter_mnemonic, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Basic validation: mnemonic should be 12 words
        String[] words = mnemonic.split("\\s+");
        if (words.length != 12) {
            Toast.makeText(requireContext(), R.string.LightningConfig__invalid_mnemonic, Toast.LENGTH_LONG).show();
            return;
        }
        
        setLoading(true);
        
        new Thread(() -> {
            try {
                // API key is optional for Spark
                boolean success = LightningUiInteractor.configureSpark(
                    AppDependencies.getApplication(), 
                    mnemonic, 
                    TextUtils.isEmpty(apiKey) ? null : apiKey
                );
                handleConnectionResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to configure Spark", t);
                handleConnectionError();
            }
        }).start();
    }
    
    private void handleConnectionResult(boolean success) {
        if (success) {
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
    }
    
    private void handleConnectionError() {
        requireView().post(() -> {
            setLoading(false);
            Toast.makeText(requireContext(), R.string.LightningConfig__configuration_failed, Toast.LENGTH_LONG).show();
        });
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

    private void enterEditMode() {
        // Get the current config to populate fields
        LightningConfig config = LightningUiInteractor.getConfig(AppDependencies.getApplication());
        LightningNodeType nodeType = config != null ? config.getType() : null;
        
        if (nodeType != null) {
            selectedNodeType = nodeType;
        }
        
        // Show the configuration UI
        requireView().findViewById(R.id.lightning_node_type_layout).setVisibility(View.VISIBLE);
        showSectionForNodeType(selectedNodeType);
        
        // Populate input fields with existing config values
        if (config != null) {
            populateFieldsFromConfig(config);
        }
        
        // Update status to indicate edit mode
        statusText.setText(R.string.LightningConfig__editing_connection);
        
        // Show connect button (to save changes), hide disconnect and edit buttons
        connectButton.setVisibility(View.VISIBLE);
        connectButton.setText(R.string.LightningConfig__save_changes);
        disconnectButton.setVisibility(View.GONE);
        editButton.setVisibility(View.GONE);
        testButton.setVisibility(View.GONE);
        balanceSection.setVisibility(View.GONE);
        
        // Pre-select the node type in the dropdown
        for (Map.Entry<String, LightningNodeType> entry : nodeTypeMap.entrySet()) {
            if (entry.getValue() == selectedNodeType) {
                nodeTypeSelector.setText(entry.getKey(), false);
                break;
            }
        }
    }
    
    private void populateFieldsFromConfig(LightningConfig config) {
        switch (config.getType()) {
            case NWC:
                nwcUriInput.setText(config.getCredential());
                break;
            case LND:
                lndUrlInput.setText(config.getUrl());
                lndMacaroonInput.setText(config.getCredential());
                break;
            case CLN:
                clnUrlInput.setText(config.getUrl());
                clnRuneInput.setText(config.getCredential());
                break;
            case PHOENIXD:
                phoenixdUrlInput.setText(config.getUrl());
                phoenixdPasswordInput.setText(config.getCredential());
                break;
            case STRIKE:
                strikeApiKeyInput.setText(config.getCredential());
                break;
            case BLINK:
                blinkApiKeyInput.setText(config.getCredential());
                break;
            case SPEED:
                speedApiKeyInput.setText(config.getCredential());
                break;
            case SPARK:
                sparkMnemonicInput.setText(config.getCredential());
                if (config.getSecondaryCredential() != null && !config.getSecondaryCredential().isEmpty()) {
                    sparkApiKeyInput.setText(config.getSecondaryCredential());
                    // Show advanced section if API key is set
                    sparkAdvancedSection.setVisibility(View.VISIBLE);
                    sparkAdvancedButton.setText(R.string.LightningConfig__hide_advanced);
                }
                break;
        }
    }

    private void setLoading(boolean loading) {
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!loading);
        disconnectButton.setEnabled(!loading);
        editButton.setEnabled(!loading);
        testButton.setEnabled(!loading);
        nodeTypeSelector.setEnabled(!loading);
    }
}
