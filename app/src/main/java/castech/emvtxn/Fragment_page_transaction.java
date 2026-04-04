package castech.emvtxn;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;


public class Fragment_page_transaction extends Fragment
{
	private static MainActivity mainActivity = null;
	private View view;
	private boolean isATMMode = false;

	// ATM Mode UI elements
	private TextView txvTransactionType;
	private TextView txvAmount;
	private TextView txvFee;
	private TextView txvInstruction;
	private TextView txvStatus;
	private ProgressBar progressBar;
	private Button btnCancel;
	private Button btnSimulateCard;

	// Legacy UI elements (for non-ATM mode)
	private static Button btnTransaction = null;
	private static Button btnClearMsg = null;
	private static Button btnInitialize = null;
	private static Switch swShowLog = null;
	private static TextView txtViewLog = null;
	private static EditText edtLog = null;
	private static LinearLayout layoutLog = null;
	private static TextView txvLog = null;
	private static Button btnMinLog = null;
	private static Button btnEncryp = null;
	private static Button btnSetting = null;
	private static TextView txvEncryp = null;
	private static Button btnC1, btnC2, btnC3, btnC4, btnC5, btnC6, btnC7, btnC8;
	private static Button btnClean = null;
	private static Button btnEncrypOK = null;
	private static LinearLayout layoutEncryp = null;
	private EditText edtAmount;

	public Fragment_page_transaction() {
	}

	public Fragment_page_transaction(MainActivity activity) {
		if (this.mainActivity == null) {
			this.mainActivity = activity;
		}
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		try {
			// Check if we're in ATM mode
			isATMMode = isInATMMode();

			if (isATMMode) {
				return createATMView(inflater, container);
			} else {
				return createLegacyView(inflater, container);
			}
		} catch (Exception e) {
			// Fallback - return empty view to prevent crash
			android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
			fallback.setBackgroundColor(0xFFFF0000); // Red = error indicator
			return fallback;
		}
	}

	private boolean isInATMMode() {
		// ATM mode if amount is pre-selected OR balance inquiry mode
		return (GlobalPara.atmSelectedAmount != null && !"0.00".equals(GlobalPara.atmSelectedAmount))
				|| GlobalPara.atmBalanceInquiryMode;
	}

	/**
	 * Create clean ATM transaction view
	 */
	private View createATMView(LayoutInflater inflater, ViewGroup container) {
		try {
			view = inflater.inflate(R.layout.fragment_page_transaction_atm, container, false);

			// Initialize ATM UI elements
			txvTransactionType = view.findViewById(R.id.txvTransactionType);
			txvAmount = view.findViewById(R.id.txvAmount);
			txvFee = view.findViewById(R.id.txvFee);
			txvInstruction = view.findViewById(R.id.txvInstruction);
			txvStatus = view.findViewById(R.id.txvStatus);
			progressBar = view.findViewById(R.id.progressBar);
			btnCancel = view.findViewById(R.id.btnCancel);

			// Set up LED indicators (with null check for emulator)
			if (GlobalPara.clLED != null) {
				GlobalPara.clLED.setImgView(
						(ImageView) view.findViewById(R.id.imageView4),
						(ImageView) view.findViewById(R.id.imageView3),
						(ImageView) view.findViewById(R.id.imageView2),
						(ImageView) view.findViewById(R.id.imageView1));
			}

			// Hidden transaction button for SDK compatibility
			btnTransaction = view.findViewById(R.id.btnTransaction);
			if (btnTransaction != null) {
				btnTransaction.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						btnTransaction_Click(v);
					}
				});
			}

			// Hidden amount field for SDK compatibility
			edtAmount = view.findViewById(R.id.edtAmt);
			GlobalPara.edtamount = edtAmount;

			// Configure display based on transaction type
			if (GlobalPara.atmBalanceInquiryMode) {
				if (txvTransactionType != null) txvTransactionType.setText("BALANCE INQUIRY");
				if (txvAmount != null) txvAmount.setVisibility(View.GONE);
				if (txvFee != null) txvFee.setVisibility(View.GONE);
				// Set amount to 0 for balance inquiry
				if (edtAmount != null) {
					edtAmount.setText("0");
					GlobalPara.strAmount = "0";
				}
			} else {
				if (txvTransactionType != null) txvTransactionType.setText("WITHDRAWAL");
				if (txvAmount != null) txvAmount.setText("$" + GlobalPara.atmSelectedAmount);
				if (txvFee != null) txvFee.setText("Fee: $" + GlobalPara.atmFee + "  |  Total: $" + GlobalPara.atmTotal);

				// Set amount in hidden field for SDK
				if (edtAmount != null) {
					// Convert dollars to cents for SDK
					try {
						double amount = Double.parseDouble(GlobalPara.atmTotal);
						int cents = (int) (amount * 100);
						edtAmount.setText(String.valueOf(cents));
						GlobalPara.strAmount = String.valueOf(cents);
					} catch (Exception e) {
						edtAmount.setText("1000");
					}
				}
			}

			// Cancel button
			if (btnCancel != null) {
				btnCancel.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						cancelTransaction();
					}
				});
			}

			// Simulate Card button - HIDDEN (only for emulator testing)
			btnSimulateCard = view.findViewById(R.id.btnSimulateCard);
			if (btnSimulateCard != null) {
				btnSimulateCard.setVisibility(View.GONE);
			}

			// ATM Mode: Skip simulated PIN entry - EMV SDK will handle secure PIN via KMS2
			// The Castle KMS2 secure PIN pad is triggered by the EMV SDK during card processing
			// when online PIN is required, storing the encrypted PIN block in GlobalPara.atmEncryptedPinBlock
			pinEntryComplete = true; // Mark as complete so card detection can start
			updateInstruction("Insert, Tap, or Swipe Card");
			updateStatus("Ready for card...");

		} catch (Exception e) {
			android.util.Log.e("Fragment_Txn", "Error in createATMView: " + e.getMessage());
			e.printStackTrace();
		}

		return view;
	}

	// PIN entry state
	private StringBuilder pinDigits = new StringBuilder();
	private TextView pinDisplay;
	private android.app.AlertDialog pinDialog;
	private boolean pinEntryComplete = false;

	/**
	 * Simulate a card transaction for testing on emulator
	 * PIN has already been entered at this point
	 */
	private void simulateCardTransaction() {
		updateInstruction("Processing...");
		updateStatus("Card detected (simulated)");
		showProgress(true);

		// Simulate card read and transaction processing
		view.postDelayed(new Runnable() {
			@Override
			public void run() {
				showProgress(false);
				updateInstruction("APPROVED");
				updateStatus("Transaction Approved");

				// Set simulated response data
				GlobalPara.atmAuthCode = "SIM123";
				GlobalPara.atmReferenceNumber = "REF" + System.currentTimeMillis();
				GlobalPara.atmResponseCode = "00";
				GlobalPara.atmResponseMessage = "APPROVED";
				GlobalPara.atmTransactionComplete = true;
				GlobalPara.atmLastFourDigits = "1234";

				// Play success feedback
				if (GlobalPara.audio != null) {
					GlobalPara.audio.playOKSound();
				}

				// Navigate to receipt after short delay
				view.postDelayed(new Runnable() {
					@Override
					public void run() {
						if (mainActivity != null) {
							mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
						}
					}
				}, 1500);
			}
		}, 2000);
	}

	/**
	 * Show PIN entry dialog - using LinearLayouts for compatibility
	 */
	private void showPinEntryDialog() {
		if (getActivity() == null || !isAdded()) return;

		try {
			pinDigits = new StringBuilder();

			// Main layout
			android.widget.LinearLayout layout = new android.widget.LinearLayout(getActivity());
			layout.setOrientation(android.widget.LinearLayout.VERTICAL);
			layout.setPadding(20, 10, 20, 10);
			layout.setGravity(android.view.Gravity.CENTER);

			// PIN display
			pinDisplay = new TextView(getActivity());
			pinDisplay.setTextSize(24);
			pinDisplay.setTextColor(android.graphics.Color.BLACK);
			pinDisplay.setGravity(android.view.Gravity.CENTER);
			pinDisplay.setText("____");
			pinDisplay.setPadding(0, 10, 0, 20);
			layout.addView(pinDisplay);

			// Create rows using LinearLayouts instead of GridLayout
			String[][] rows = {
				{"1", "2", "3"},
				{"4", "5", "6"},
				{"7", "8", "9"},
				{"CLR", "0", "OK"}
			};

			// Get screen density for dp conversion
			float density = getResources().getDisplayMetrics().density;
			int buttonSize = (int) (70 * density); // 70dp
			int margin = (int) (4 * density); // 4dp

			for (String[] row : rows) {
				android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(getActivity());
				rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
				rowLayout.setGravity(android.view.Gravity.CENTER);

				for (String label : row) {
					Button btn = new Button(getActivity());
					btn.setText(label);
					btn.setTextSize(18);
					btn.setTextColor(android.graphics.Color.BLACK); // Default text color
					btn.setAllCaps(false); // Prevent uppercase transformation

					android.widget.LinearLayout.LayoutParams params =
						new android.widget.LinearLayout.LayoutParams(buttonSize, buttonSize);
					params.setMargins(margin, margin, margin, margin);
					btn.setLayoutParams(params);
					btn.setPadding(0, 0, 0, 0); // Remove default padding
					btn.setMinimumWidth(0);
					btn.setMinimumHeight(0);
					btn.setMinWidth(0);
					btn.setMinHeight(0);

					if ("CLR".equals(label)) {
						btn.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"));
						btn.setTextColor(android.graphics.Color.WHITE);
					} else if ("OK".equals(label)) {
						btn.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"));
						btn.setTextColor(android.graphics.Color.WHITE);
					} else {
						btn.setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"));
					}

					final String btnLabel = label;
					btn.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							onPinButtonClick(btnLabel);
						}
					});
					rowLayout.addView(btn);
				}
				layout.addView(rowLayout);
			}

			// Build dialog
			android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getActivity());
			builder.setView(layout);
			builder.setCancelable(false);

			pinDialog = builder.create();
			pinDialog.show();

		} catch (Exception e) {
			android.util.Log.e("Fragment_Txn", "Error showing PIN dialog: " + e.getMessage());
		}
	}

	/**
	 * Handle PIN pad button click
	 */
	private void onPinButtonClick(String label) {
		if ("CLR".equals(label)) {
			// Clear PIN
			pinDigits = new StringBuilder();
			updatePinDisplay();
		} else if ("OK".equals(label)) {
			// Submit PIN
			if (pinDigits.length() == 4) {
				if (pinDialog != null) {
					pinDialog.dismiss();
					pinDialog = null;
				}
				processPinEntry(pinDigits.toString());
			}
		} else {
			// Add digit (max 4)
			if (pinDigits.length() < 4) {
				pinDigits.append(label);
				updatePinDisplay();
			}
		}
	}

	/**
	 * Update PIN display with dots
	 */
	private void updatePinDisplay() {
		StringBuilder display = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			if (i < pinDigits.length()) {
				display.append("●");
			} else {
				display.append("_");
			}
		}
		if (pinDisplay != null) {
			pinDisplay.setText(display.toString());
		}
	}

	/**
	 * Process the entered PIN - mark complete and show card waiting screen
	 * NOTE: This is only used for emulator testing. On real terminal, Castle KMS2 handles PIN.
	 */
	private void processPinEntry(String pin) {
		// For emulator testing only - real terminal uses Castle KMS2 secure PIN pad
		// which stores the encrypted PIN block directly in GlobalPara.atmEncryptedPinBlock
		GlobalPara.atmEncryptedPinBlock = "EMULATOR_TEST_PIN_BLOCK";
		pinEntryComplete = true;

		// Show card waiting screen
		updateInstruction("Insert, Tap, or Swipe Card");
		updateStatus("Ready for card...");

		// Play confirmation beep
		if (GlobalPara.audio != null) {
			GlobalPara.audio.playOKSound();
		}
	}

	/**
	 * Create legacy EMV sample view (for non-ATM mode)
	 */
	private View createLegacyView(LayoutInflater inflater, ViewGroup container) {
		view = inflater.inflate(R.layout.fragment_page_transaction, container, false);

		this.btnTransaction = (Button)view.findViewById(R.id.btnTransaction);
		this.btnTransaction.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				btnTransaction_Click(v);
			}
		});

		this.btnClearMsg = (Button)view.findViewById(R.id.btnClearMsg);
		this.btnClearMsg.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				btnClearMsg_Click(v);
			}
		});

		this.txtViewLog = (TextView)view.findViewById(R.id.txtViewLog);
		this.layoutLog = (LinearLayout)view.findViewById(R.id.layoutLog);

		this.txvLog = (TextView)view.findViewById(R.id.txvLog);
		this.txvLog.setMovementMethod(new ScrollingMovementMethod());

		this.edtLog = (EditText)view.findViewById(R.id.edtLog);
		this.edtLog.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				layoutLog.setVisibility(View.VISIBLE);
				txvLog.setText(edtLog.getText());
			}
		});

		this.btnMinLog = (Button)view.findViewById(R.id.btnMinLog);
		this.btnMinLog.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				layoutLog.setVisibility(View.INVISIBLE);
			}
		});

		this.swShowLog = (Switch)view.findViewById(R.id.swShowLog);
		this.swShowLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				swShowLog_Change(isChecked);
			}
		});

		this.btnEncryp = (Button)view.findViewById(R.id.btnEncryp);
		this.btnEncryp.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				layoutEncryp = (LinearLayout)view.findViewById(R.id.layoutEncryp);
				layoutEncryp.setVisibility(View.VISIBLE);
				GlobalPara.token = new int[8];
				GlobalPara.token_i = 0;
				txvEncryp.setText("");
				enableTokenButtons(true);
			}
		});

		this.btnSetting = (Button)view.findViewById(R.id.btnSetting);
		this.btnSetting.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				btnSetting_Click(v);
			}
		});

		GlobalPara.token = new int[8];
		GlobalPara.token_i = 0;
		this.txvEncryp = (TextView)view.findViewById(R.id.txvEncryp);
		setupTokenButtons();

		if (GlobalPara.clLED != null) {
			GlobalPara.clLED.setImgView((ImageView) view.findViewById(R.id.imageView4),
					(ImageView) view.findViewById(R.id.imageView3),
					(ImageView) view.findViewById(R.id.imageView2),
					(ImageView) view.findViewById(R.id.imageView1));
		}

		edtAmount = (EditText) view.findViewById(R.id.edtAmt);
		GlobalPara.edtamount = edtAmount;
		if (edtAmount != null && edtAmount.getText() != null) {
			GlobalPara.strAmount = edtAmount.getText().toString();
		}

		return view;
	}

	private void setupTokenButtons() {
		this.btnC1 = (Button)view.findViewById(R.id.btnC1);
		this.btnC2 = (Button)view.findViewById(R.id.btnC2);
		this.btnC3 = (Button)view.findViewById(R.id.btnC3);
		this.btnC4 = (Button)view.findViewById(R.id.btnC4);
		this.btnC5 = (Button)view.findViewById(R.id.btnC5);
		this.btnC6 = (Button)view.findViewById(R.id.btnC6);
		this.btnC7 = (Button)view.findViewById(R.id.btnC7);
		this.btnC8 = (Button)view.findViewById(R.id.btnC8);

		View.OnClickListener tokenListener = new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				int tokenVal = 0;
				switch (v.getId()) {
					case R.id.btnC1: tokenVal = 0xC1; btnC1.setEnabled(false); break;
					case R.id.btnC2: tokenVal = 0xC2; btnC2.setEnabled(false); break;
					case R.id.btnC3: tokenVal = 0xC3; btnC3.setEnabled(false); break;
					case R.id.btnC4: tokenVal = 0xC4; btnC4.setEnabled(false); break;
					case R.id.btnC5: tokenVal = 0xC5; btnC5.setEnabled(false); break;
					case R.id.btnC6: tokenVal = 0xC6; btnC6.setEnabled(false); break;
					case R.id.btnC7: tokenVal = 0xC7; btnC7.setEnabled(false); break;
					case R.id.btnC8: tokenVal = 0xC8; btnC8.setEnabled(false); break;
				}
				GlobalPara.token[GlobalPara.token_i++] = tokenVal;
				updateTokenDisplay();
			}
		};

		if (btnC1 != null) btnC1.setOnClickListener(tokenListener);
		if (btnC2 != null) btnC2.setOnClickListener(tokenListener);
		if (btnC3 != null) btnC3.setOnClickListener(tokenListener);
		if (btnC4 != null) btnC4.setOnClickListener(tokenListener);
		if (btnC5 != null) btnC5.setOnClickListener(tokenListener);
		if (btnC6 != null) btnC6.setOnClickListener(tokenListener);
		if (btnC7 != null) btnC7.setOnClickListener(tokenListener);
		if (btnC8 != null) btnC8.setOnClickListener(tokenListener);

		this.btnClean = (Button)view.findViewById(R.id.btnClean);
		if (btnClean != null) {
			btnClean.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					GlobalPara.token = new int[8];
					GlobalPara.token_i = 0;
					txvEncryp.setText("");
					enableTokenButtons(true);
				}
			});
		}

		this.btnEncrypOK = (Button)view.findViewById(R.id.btnEncrypOK);
		if (btnEncrypOK != null) {
			btnEncrypOK.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					GlobalPara.isTokenOK = true;
					layoutEncryp = (LinearLayout)view.findViewById(R.id.layoutEncryp);
					layoutEncryp.setVisibility(View.INVISIBLE);
					btnEncryp_Click(v);
				}
			});
		}
	}

	private void enableTokenButtons(boolean enabled) {
		if (btnC1 != null) btnC1.setEnabled(enabled);
		if (btnC2 != null) btnC2.setEnabled(enabled);
		if (btnC3 != null) btnC3.setEnabled(enabled);
		if (btnC4 != null) btnC4.setEnabled(enabled);
		if (btnC5 != null) btnC5.setEnabled(enabled);
		if (btnC6 != null) btnC6.setEnabled(enabled);
		if (btnC7 != null) btnC7.setEnabled(enabled);
		if (btnC8 != null) btnC8.setEnabled(enabled);
	}

	private void updateTokenDisplay() {
		if (txvEncryp != null) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < GlobalPara.token_i; i++) {
				sb.append(String.format("%02X", GlobalPara.token[i]));
			}
			txvEncryp.setText(sb.toString());
		}
	}

	@Override
	public void setUserVisibleHint(boolean isVisibleToUser) {
		super.setUserVisibleHint(isVisibleToUser);

		// Called when ViewPager changes pages
		if (isVisibleToUser && isAdded()) {
			// Check if mode has changed since view was created
			boolean currentlyInATMMode = isInATMMode();
			if (currentlyInATMMode != isATMMode) {
				// Mode changed - need to refresh the view
				isATMMode = currentlyInATMMode;
				refreshView();
			} else if (isATMMode) {
				// Already in ATM mode, just refresh display
				refreshATMDisplay();
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		// Check if mode has changed since view was created
		boolean currentlyInATMMode = isInATMMode();
		if (currentlyInATMMode != isATMMode) {
			// Mode changed - need to refresh the view
			isATMMode = currentlyInATMMode;
			refreshView();
		}

		if (GlobalPara.isInitThreadFinish == true) {
			if (isATMMode) {
				// ATM Mode: Update display and auto-start transaction
				refreshATMDisplay();
				updateStatus("Ready for card...");

				// Auto-click hidden transaction button after short delay
				if (btnTransaction != null) {
					view.postDelayed(new Runnable() {
						@Override
						public void run() {
							if (btnTransaction != null) {
								btnTransaction.performClick();
							}
						}
					}, 500);
				}
			} else {
				// Normal mode
				if (GlobalPara.mainActivity != null) {
					GlobalPara.mainActivity.ui_EnableAllButton();
					GlobalPara.mainActivity.ui_ShowMsg("Welcome\n");
				}
			}
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		// Dismiss PIN dialog if showing to prevent window leak
		if (pinDialog != null && pinDialog.isShowing()) {
			pinDialog.dismiss();
		}
		pinDialog = null;

		// Remove any pending callbacks on the view
		if (view != null) {
			view.removeCallbacks(null);
		}
	}

	/**
	 * Public method to check and refresh mode - called from MainActivity.navigateToPage()
	 */
	public void checkAndRefreshMode() {
		boolean currentlyInATMMode = isInATMMode();

		// ATM Mode: PIN is handled by Castle KMS2 secure PIN pad during card processing
		pinEntryComplete = true;

		if (currentlyInATMMode != isATMMode) {
			isATMMode = currentlyInATMMode;
			refreshView();
		} else if (currentlyInATMMode) {
			// Same mode but new transaction - ready for card (PIN handled by EMV SDK)
			updateInstruction("Insert, Tap, or Swipe Card");
			updateStatus("Ready for card...");
		}

		// Auto-start transaction in ATM mode if SDK is initialized
		if (currentlyInATMMode && GlobalPara.isInitThreadFinish && btnTransaction != null && view != null) {
			view.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (btnTransaction != null) {
						android.util.Log.d("Fragment_Txn", "Auto-clicking transaction button from checkAndRefreshMode");
						btnTransaction.performClick();
					}
				}
			}, 500);
		}
	}

	/**
	 * Refresh the view when mode changes - replace view content directly
	 */
	private void refreshView() {
		if (getView() == null || getActivity() == null) return;

		try {
			ViewGroup container = (ViewGroup) getView();
			container.removeAllViews();

			LayoutInflater inflater = LayoutInflater.from(getActivity());
			View newContent;

			if (isATMMode) {
				newContent = inflater.inflate(R.layout.fragment_page_transaction_atm, container, false);
				container.addView(newContent);

				// Re-initialize ATM UI elements
				txvTransactionType = newContent.findViewById(R.id.txvTransactionType);
				txvAmount = newContent.findViewById(R.id.txvAmount);
				txvFee = newContent.findViewById(R.id.txvFee);
				txvInstruction = newContent.findViewById(R.id.txvInstruction);
				txvStatus = newContent.findViewById(R.id.txvStatus);
				progressBar = newContent.findViewById(R.id.progressBar);
				btnCancel = newContent.findViewById(R.id.btnCancel);
				btnSimulateCard = newContent.findViewById(R.id.btnSimulateCard);

				// Hidden transaction button for SDK compatibility
				btnTransaction = newContent.findViewById(R.id.btnTransaction);
				if (btnTransaction != null) {
					btnTransaction.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							btnTransaction_Click(v);
						}
					});
				}

				// Hidden amount field for SDK compatibility
				edtAmount = newContent.findViewById(R.id.edtAmt);
				GlobalPara.edtamount = edtAmount;

				// Set amount for balance inquiry (0 cents)
				if (GlobalPara.atmBalanceInquiryMode) {
					if (edtAmount != null) {
						edtAmount.setText("0");
						GlobalPara.strAmount = "0";
					}
				}

				// Set up LED indicators (with null check)
				if (GlobalPara.clLED != null) {
					GlobalPara.clLED.setImgView(
							(ImageView) newContent.findViewById(R.id.imageView4),
							(ImageView) newContent.findViewById(R.id.imageView3),
							(ImageView) newContent.findViewById(R.id.imageView2),
							(ImageView) newContent.findViewById(R.id.imageView1));
				}

				// Set up button listeners
				if (btnCancel != null) {
					btnCancel.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							cancelTransaction();
						}
					});
				}
				// Simulate Card button - HIDDEN
				if (btnSimulateCard != null) {
					btnSimulateCard.setVisibility(View.GONE);
				}

				// Update display
				refreshATMDisplay();

				// Store reference
				view = newContent;

				// ATM Mode: Skip simulated PIN entry - EMV SDK handles secure PIN via KMS2
				pinEntryComplete = true;
				updateInstruction("Insert, Tap, or Swipe Card");
				updateStatus("Ready for card...");
			} else {
				newContent = inflater.inflate(R.layout.fragment_page_transaction, container, false);
				container.addView(newContent);
				view = newContent;
				// Legacy view setup would go here
			}
		} catch (Exception e) {
			android.util.Log.e("Fragment_Txn", "Error in refreshView: " + e.getMessage());
		}
	}

	/**
	 * Refresh ATM display with current values
	 */
	private void refreshATMDisplay() {
		if (txvTransactionType != null) {
			if (GlobalPara.atmBalanceInquiryMode) {
				txvTransactionType.setText("BALANCE INQUIRY");
				if (txvAmount != null) txvAmount.setVisibility(View.GONE);
				if (txvFee != null) txvFee.setVisibility(View.GONE);
			} else {
				txvTransactionType.setText("WITHDRAWAL");
				if (txvAmount != null) {
					txvAmount.setVisibility(View.VISIBLE);
					txvAmount.setText("$" + GlobalPara.atmSelectedAmount);
				}
				if (txvFee != null) {
					txvFee.setVisibility(View.VISIBLE);
					txvFee.setText("Fee: $" + GlobalPara.atmFee + "  |  Total: $" + GlobalPara.atmTotal);
				}
			}
		}
	}

	/**
	 * Update the status message (ATM mode only)
	 */
	public void updateStatus(String message) {
		if (txvStatus != null) {
			txvStatus.setText(message);
		}
	}

	/**
	 * Update the instruction text (ATM mode only)
	 */
	public void updateInstruction(String instruction) {
		if (txvInstruction != null) {
			txvInstruction.setText(instruction);
		}
	}

	/**
	 * Show/hide progress indicator
	 */
	public void showProgress(boolean show) {
		if (progressBar != null) {
			progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
		}
	}

	/**
	 * Cancel the current transaction
	 */
	private void cancelTransaction() {
		android.util.Log.d("Fragment_Txn", "cancelTransaction() called");

		// Full reset of ATM state
		GlobalPara.resetATMTransactionState();

		// Also try to abort any in-progress EMV transaction
		if (mainActivity != null) {
			try {
				mainActivity.abortTransaction();
			} catch (Exception e) {
				android.util.Log.e("Fragment_Txn", "Error aborting transaction: " + e.getMessage());
			}
		}

		// Navigate back to main menu
		if (mainActivity != null) {
			mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
		}
	}

	public View toView() {
		return view;
	}

	public int btnTransaction_Click(View view) {
		android.util.Log.d("Fragment_Txn", "btnTransaction_Click called, mainActivity=" + (mainActivity != null));
		if (mainActivity != null) {
			mainActivity.btnTransaction_Click(view);
		} else {
			android.util.Log.e("Fragment_Txn", "ERROR: mainActivity is null!");
		}
		return 0;
	}

	public int btnClearMsg_Click(View view) {
		this.mainActivity.btnClearMsg_Click(view);
		return 0;
	}

	public int swShowLog_Change(boolean isChecked) {
		if (txtViewLog != null) {
			txtViewLog.setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
		}
		if (edtLog != null) {
			edtLog.setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
		}
		if (btnClearMsg != null) {
			btnClearMsg.setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
		}
		return 0;
	}

	public int btnEncryp_Click(View view) {
		this.mainActivity.btnEncryp_Click(view);
		return 0;
	}

	public int btnSetting_Click(View view) {
		this.mainActivity.btnSetting_Click(view);
		return 0;
	}
}
