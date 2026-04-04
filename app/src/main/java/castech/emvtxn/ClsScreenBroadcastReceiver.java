package castech.emvtxn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;

import CTOS.CtEMV;


public class ClsScreenBroadcastReceiver extends BroadcastReceiver
{
	static final boolean d_VPIN_IS_PERFORMING = true;
	static final boolean d_VPIN_IS_NOT_PERFORMING = false;

	boolean vPINStatus = d_VPIN_IS_NOT_PERFORMING;
	long lastMilliSec = 0;
	CtEMV emv = null;

	public ClsScreenBroadcastReceiver(CtEMV inputEMV)
	{
		vPINStatus = d_VPIN_IS_NOT_PERFORMING;
		lastMilliSec = 0;
		emv = inputEMV;
	}

	public void setVirtualPINStatus(boolean status)
	{
		vPINStatus = status;
	}

	@Override
	public void onReceive(Context context, Intent intent)
	{
		boolean isCheckCancelPINProcessNeed;
		boolean isDoCancelPIN;
		String strAction;
		long now;
		int intRtn;

		isCheckCancelPINProcessNeed = false;
		isDoCancelPIN = false;
		strAction = intent.getAction();

		if(Intent.ACTION_SCREEN_OFF.equals(strAction))
		{
			Log.d("MyScrnRecver", "Screen OFF");

			isCheckCancelPINProcessNeed = true;
		}
		else if(Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(strAction))
		{
			String reason = intent.getStringExtra("reason");
			if(reason != null)
			{
				if (reason.equalsIgnoreCase("globalactions"))
				{
					Log.d("MyScrnRecver", "Power Button Long Pressed");

					isCheckCancelPINProcessNeed = true;
				}
			}
		}
		else
		{
			//nothing
		}


		if(isCheckCancelPINProcessNeed == true)
		{
			if(this.lastMilliSec == 0)
			{
				//Log.d("MyScrnRecver", "lastMilliSec == 0");

				isDoCancelPIN = true;
				this.lastMilliSec = Calendar.getInstance().getTimeInMillis();
			}
			else
			{
				now = Calendar.getInstance().getTimeInMillis();
				if(now - this.lastMilliSec > 300)	//To avoid screen on/off frequently
				{
					isDoCancelPIN = true;
					this.lastMilliSec = now;
				}
			}

			if(isDoCancelPIN == true)
			{
				if(vPINStatus == d_VPIN_IS_PERFORMING)
				{
					if(this.emv != null)
					{
						MyUtility.sleep(1000);	//Add some response time for secure module switch/process touch panel control

						intRtn = this.emv.cancelVirtualPIN();
						Log.d("MyScrnRecver", String.format("cancelVirtualPIN(), Rtn : 0x%08X", intRtn));
					}
				}
				else
				{
					Log.d("MyScrnRecver", "vPINStatus : d_VPIN_IS_NOT_PERFORMING");
				}
			}
		}
	}
}



