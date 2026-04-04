package castech.emvtxn;

import android.widget.ImageView;

import static castech.emvtxn.MyUtility.sleep;

class OneLED
{
    private int onImgFileID;
    private int offImgFileID;

    private ImageView visibleImgView;

    public OneLED(int onImgFileID, int offImgFileID)
    {
        this.onImgFileID = onImgFileID;
        this.offImgFileID = offImgFileID;
    }

    public void setImgView(ImageView imgView)
    {
        this.visibleImgView =  imgView;
    }

    public void turnOn()
    {
        visibleImgView.setImageResource(this.onImgFileID);
    }
    public void turnOff()
    {
        visibleImgView.setImageResource(this.offImgFileID);
    }
}

public class ClessLed
{
    static final int UI_TYPE_NORMAL = 0;
    static final int UI_TYPE_EUROPE = 1;

    private static final int LED_STATUS_NOT_READY = 0;
    private static final int LED_STATUS_IDLE = 1;
    private static final int LED_STATUS_PROCESSING = 2;
    private static final int LED_STATUS_READY_TO_READ = 3;
    private static final int LED_STATUS_CARD_READ_SUCCESSFULLY = 4;
    private static final int LED_STATUS_PROCESSING_ERROR = 5;

    private static Thread threadLEDIdle;
    private static Thread threadLED;

    private boolean isEurLedIdle;
    private int lastledStatus;
    private int uiType = UI_TYPE_NORMAL;
    private OneLED[] led;
    private MainActivity mainActivity;

	private int eruLedIdle_OnDuration = 200;	//200 ms
	private int eurLedIdle_OffDuration = 4800;
	private int eurLedReadCardOK_OnInterval = (250/2);
	private int eurLedReadOK_OffDuration = 750;

    private volatile boolean isReadCardOKProc = false;


	//------------------------------------------------------------------
	// ClessLed
	//------------------------------------------------------------------
    public ClessLed(int led1_OnImgFileID, int led1_OffImgFileID,
               int led2_OnImgFileID, int led2_OffImgFileID,
               int led3_OnImgFileID, int led3_OffImgFileID,
               int led4_OnImgFileID, int led4_OffImgFileID )
    {
        led = new OneLED[4];
        led[0] = new OneLED(led1_OnImgFileID, led1_OffImgFileID);
        led[1] = new OneLED(led2_OnImgFileID, led2_OffImgFileID);
        led[2] = new OneLED(led3_OnImgFileID, led3_OffImgFileID);
        led[3] = new OneLED(led4_OnImgFileID, led4_OffImgFileID);

	    isEurLedIdle = false;
	    lastledStatus = LED_STATUS_NOT_READY;
    }

	//------------------------------------------------------------------
	// setImgView
	//------------------------------------------------------------------
    public void setImgView(ImageView imgViewLed1, ImageView imgViewLed2, ImageView imgViewLed3, ImageView imgViewLed4)
    {
        led[0].setImgView(imgViewLed1);
        led[1].setImgView(imgViewLed2);
        led[2].setImgView(imgViewLed3);
        led[3].setImgView(imgViewLed4);
    }

	//------------------------------------------------------------------
	// setActivity
	//------------------------------------------------------------------
    public void setActivity(MainActivity mainActivity)
    {
        this.mainActivity = mainActivity;
    }

	//------------------------------------------------------------------
	// setUIType
	//------------------------------------------------------------------
    public void setUIType(int uiType)
    {
        this.uiType = UI_TYPE_NORMAL;
        if(uiType == UI_TYPE_EUROPE)
        {
            this.uiType = UI_TYPE_EUROPE;
        }
    }

	//------------------------------------------------------------------
	// setLED
	//------------------------------------------------------------------
	public int setLED(final byte index, final byte bOnOff)
	{
	    //Log.d("TAG","setLED: index=" + String.format("%H", index) + " bOnOff=" + String.format("%H", bOnOff));
		if(mainActivity == null)
		{
			return 1;
		}
		
		if(led == null)
		{
			return 2;
		}

		mainActivity.runOnUiThread(new Runnable()
									{
										@Override
										public void run()
										{
                                            byte bBit;

											for(byte i = 0 ; i < 4; i++)
											{
											    bBit = (byte)(0x01 << i);
												if((index & bBit) == bBit)
												{
													if((bOnOff & bBit) == bBit)
													{
														led[i].turnOn();
													}
													else
													{
														led[i].turnOff();
													}
												}
											}
										}
									}
                    );
        
		
		return 0;
	}

	//------------------------------------------------------------------
	// startIdleLEDBehavior
	//------------------------------------------------------------------
    public void startIdleLEDBehavior()
    {
        if(uiType == UI_TYPE_EUROPE)
        {
            if (!isEurLedIdle)
            {
				while(isReadCardOKProc == true)
				{
					sleep(5);
				}
				
                threadLEDIdle = new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
	                        isEurLedIdle = true;
	                        lastledStatus = LED_STATUS_IDLE;
                            setLED((byte)0x0F, (byte)0x00);

                            do
                            {
                                Thread.sleep(eurLedIdle_OffDuration);
                                if(!isEurLedIdle)
                                {
                                    break;
                                }

                                setLED((byte)0x08, (byte)0x08);
                                Thread.sleep(eruLedIdle_OnDuration);
                                if(!isEurLedIdle)
                                {
                                    break;
                                }

                                setLED((byte)0x08, (byte)0x00);


                            } while (isEurLedIdle);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }

                    }
                });

                threadLEDIdle.start();
            }
        }
        else
        {
			setLED((byte)0x0F, (byte)0x08);
        }
    }

	//------------------------------------------------------------------
	// stopIdleLEDBehavior
	//------------------------------------------------------------------
    public void stopIdleLEDBehavior()
    {
		if(uiType == UI_TYPE_EUROPE)
		{
			isEurLedIdle = false;
        	threadLEDIdle.interrupt();

			try
			{
				threadLEDIdle.join();
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
		}
    }

	private int getClessLedStatus(final byte index, final byte bOnOff)
    {
        int status;

        status = LED_STATUS_IDLE;
        do
		{
            //Find Card
            if((index & (byte) 0x08) == (byte)0x08 && (bOnOff & (byte) 0x08) == (byte)0x08)
            {
            	status = LED_STATUS_READY_TO_READ;
                break;
            }

            //Processing signal, not show but set status for check
			if((index & (byte) 0x04) == (byte)0x04 && (bOnOff & (byte) 0x04) == (byte)0x04)
            {
	            status = LED_STATUS_PROCESSING;
	            break;
            }

            //Read OK
			if((index & (byte) 0x02) == (byte)0x02 && (bOnOff & (byte) 0x02) == (byte)0x02)
            {
                status = LED_STATUS_CARD_READ_SUCCESSFULLY;
                break;
            }

            //Alert
			if((index & (byte) 0x01) == (byte)0x01 && (bOnOff & (byte) 0x01) == (byte)0x01)
            {
	            status = LED_STATUS_PROCESSING_ERROR;
	            break;
            }

        }while(false);

        if(status == lastledStatus)
        {
            status = LED_STATUS_NOT_READY;
        }
        else
        {
	        lastledStatus = status;
        }
		
		return status;
    }

    //------------------------------------------------------------------
    // For EMVCL event - eventEMVCLLEDPicShow
    //------------------------------------------------------------------
    public int eventEMVCLLEDPicShow(final byte index, final byte bOnOff)
    {
        if(uiType == UI_TYPE_EUROPE)
        {
            int LedStatus;
            LedStatus = getClessLedStatus(index, bOnOff);
            if(LedStatus == LED_STATUS_READY_TO_READ)
            {
	            stopIdleLEDBehavior();
                setLED((byte)0x0F, (byte)0x08);
            }
            else if(LedStatus == LED_STATUS_PROCESSING)
            {
				//For if  stopIdleLEDBehavior in the dark side (LED 1 if off), in the polling and read card time, LED 1 should always on
            	setLED((byte)0x0F, (byte)0x08);
            }
            else if(LedStatus == LED_STATUS_CARD_READ_SUCCESSFULLY)
            {
                threadLED = new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            isReadCardOKProc = true;
                            setLED((byte) 0x04, (byte) 0x04);
                            Thread.sleep(eurLedReadCardOK_OnInterval);

                            setLED((byte) 0x02, (byte) 0x02);
                            Thread.sleep(eurLedReadCardOK_OnInterval);

                            setLED((byte) 0x01, (byte) 0x01);
                            Thread.sleep(eurLedReadOK_OffDuration);

                            setLED((byte) 0x0F, (byte) 0x00);
                            isReadCardOKProc = false;
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }
                });

                threadLED.start();

            }
            else if(LedStatus == LED_STATUS_PROCESSING_ERROR)
            {
                while(isReadCardOKProc == true)
				{
					sleep(5);
				}
                setLED((byte)0x0F, (byte)0x00);
            }
        }
        else
        {
			setLED(index, bOnOff);
        }

        return 0;
    }
}