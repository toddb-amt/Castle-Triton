package castech.emvtxn;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;


public class ClsAudioInidcator
{
	private SoundPool soundPool;
	private int okToneFileResourceID;
	private int cancelkeyToneFileResourceID;
	private int alertToneFileResourceID;
	private int okToneSoundID;
	private int alertToneSoundID;
	private int cancelkeyToneSoundID;
	private Context context;


	public ClsAudioInidcator(int okToneFileID, int alertToneFileID, int cancelkeyToneFileID, Context context)
	{
		this.context = context;
		soundPool = new SoundPool(3, AudioManager.STREAM_MUSIC, 0);
		this.okToneFileResourceID = okToneFileID;
		this.alertToneFileResourceID = alertToneFileID;
		this.cancelkeyToneFileResourceID = cancelkeyToneFileID;
		okToneSoundID = soundPool.load(this.context, this.okToneFileResourceID, 1);
		alertToneSoundID = soundPool.load(this.context, this.alertToneFileResourceID, 1);
		cancelkeyToneSoundID = soundPool.load(this.context, this.cancelkeyToneFileResourceID, 1);
	}

	//play(int soundID, float leftVolume, float rightVolume, int priority, int loop, float rate)
	public int playOKSound()
	{
		return soundPool.play(okToneSoundID, 1.0F, 1.0F, 0, 0, 1.0F);
	}

	public int playAlertSound()
	{
		return soundPool.play(alertToneSoundID, 1.0F, 1.0F, 0, 0, 1.0F);
	}

	public int playCancelKeySound()
	{
		return soundPool.play(cancelkeyToneSoundID, 1.0F, 1.0F, 0, 0, 1.0F);
	}

/*
	//------------------------------------------------------------------
	// For EMVCL event
	//------------------------------------------------------------------
	public int eventEMVCLAudioIndication(short tone)
	{

		if(tone == 0x01)
		{
			return playOKSound();
		}
		else if(tone == 0x02)
		{
			return playAlertSound();
		}
		else
		{

		}

		return 0;
	}
//*/

}
