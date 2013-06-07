/*
    This file is part of Project MAXS.

    MAXS and its modules is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    MAXS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with MAXS.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.projectmaxs.main;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.projectmaxs.main.CommandInformation.CommandClashException;
import org.projectmaxs.main.util.Constants;
import org.projectmaxs.main.xmpp.XMPPService;
import org.projectmaxs.shared.Command;
import org.projectmaxs.shared.Contact;
import org.projectmaxs.shared.GlobalConstants;
import org.projectmaxs.shared.ModuleInformation;
import org.projectmaxs.shared.UserMessage;
import org.projectmaxs.shared.util.Log;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

public class MAXSService extends Service {

	private static final ScheduledExecutorService sExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread t = new Thread(runnable, "MAXS Executor Service");
			t.setDaemon(true);
			return t;
		}
	});

	private static Log sLog = Log.getLog();

	private final Map<String, CommandInformation> mCommands = new HashMap<String, CommandInformation>();

	private XMPPService mXMPPService;

	private final Object mRecentContactLock = new Object();
	private ScheduledFuture<?> mSetRecentContactFeature;
	private volatile Contact mRecentContact;

	private final IBinder mBinder = new LocalBinder();

	public void onCreate() {
		super.onCreate();
		sLog.initialize(Settings.getInstance(this).getLogSettings());
		mXMPPService = new XMPPService(this);
		// Start the service the connection was previously established
		if (Settings.getInstance(this).getXMPPConnectionState()) startService();
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null) {
			// The service has been killed by Android and we try to restart
			// the connection. This null intent behavior is only for SDK < 9
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
				startService(new Intent(Constants.ACTION_START_SERVICE));
			}
			else {
				sLog.w("onStartCommand() null intent with Gingerbread or higher");
			}
			return START_STICKY;
		}
		String action = intent.getAction();
		if (action.equals(Constants.ACTION_START_SERVICE)) {
			// let's assume that if the size is zero we have to do an initial
			// challenge
			if (mCommands.size() == 0) {
				// clear commands before challenging the modules to register
				mCommands.clear();
				sendBroadcast(new Intent(GlobalConstants.ACTION_REGISTER));
			}
			mXMPPService.connect();
			return START_STICKY;
		}
		else if (action.equals(Constants.ACTION_STOP_SERVICE)) {
			mXMPPService.disconnect();
			return START_NOT_STICKY;
		}
		// TODO everything else
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public class LocalBinder extends Binder {
		public MAXSService getService() {
			return MAXSService.this;
		}
	}

	public XMPPService getXMPPService() {
		return mXMPPService;
	}

	public enum CommandOrigin {
		XMPP
	}

	/**
	 * args can be also in the place of subCmd if the default subCmd is wanted
	 * 
	 * @param command
	 * @param subCmd
	 * @param args
	 * @param issuer
	 * @param issuerInformation
	 */
	public void performCommand(String command, String subCmd, String args, CommandOrigin origin,
			String issuerInformation) {

		int id = Settings.getInstance(this).getNextCommandId();
		// TODO Database entry of the command goes here

		CommandInformation ci = mCommands.get(command);
		if (ci == null) {
			sendUserMessage(new UserMessage("Unkown command: " + command, id));
			return;
		}

		if (subCmd == null) {
			subCmd = ci.getDefaultSubCommand();
		}
		else if (!ci.isKnownSubCommand(subCmd)) {
			// If subCmd is not known, then maybe it is not really a sub command
			// but instead arguments. Therefore we have to lookup the
			// default sub command when arguments are given, but first assign
			// args to subCmd
			args = subCmd;
			subCmd = ci.getDefaultSubcommandWithArgs();
		}

		if (subCmd == null) {
			sendUserMessage(new UserMessage("Unknown subCommand: " + subCmd == null ? args : subCmd, id));
			return;
		}

		String modulePackage = ci.getPackageForSubCommand(subCmd);
		Intent intent = new Intent(GlobalConstants.ACTION_PERFORM_COMMAND);
		intent.putExtra(GlobalConstants.EXTRA_COMMAND, new Command(command, subCmd, args, id));
		intent.setClassName(modulePackage, modulePackage + ".ModuleService");
		startService(intent);
	}

	public void startService() {
		Intent intent = new Intent(Constants.ACTION_START_SERVICE);
		startService(intent);
	}

	public void stopService() {
		Intent intent = new Intent(Constants.ACTION_STOP_SERVICE);
		startService(intent);
	}

	public void registerModule(ModuleInformation moduleInformation) {
		String modulePackage = moduleInformation.getModulePackage();
		Set<ModuleInformation.Command> cmds = moduleInformation.getCommands();
		synchronized (mCommands) {
			for (ModuleInformation.Command c : cmds) {
				String cStr = c.getCommand();
				CommandInformation ci = mCommands.get(cStr);
				if (ci == null) {
					ci = new CommandInformation(cStr);
					mCommands.put(cStr, ci);
				}
				try {
					ci.addSubAndDefCommands(c, modulePackage);
				} catch (CommandClashException e) {
					throw new IllegalStateException(e); // TODO
				}
			}

		}
	}

	public Contact getRecentContact() {
		Contact res = null;
		synchronized (mRecentContactLock) {
			res = mRecentContact;
		}
		return res;
	}

	public void setRecentContact(final Contact contact) {
		synchronized (mRecentContactLock) {
			if (mSetRecentContactFeature != null) {
				mSetRecentContactFeature.cancel(false);
				mSetRecentContactFeature = null;
			}
			mSetRecentContactFeature = sExecutor.schedule(new Runnable() {
				@Override
				public void run() {
					synchronized (mRecentContactLock) {
						mRecentContact = contact;
					}
				}
			}, 5, TimeUnit.SECONDS);
		}

	}

	public Contact getContactFromAlias(String alias) {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateXMPPStatusInformation(String type, String info) {
		// TODO Auto-generated method stub

	}

	public void sendUserMessage(UserMessage userMsg) {
		int id = userMsg.getId();
		String to = null;
		if (id != UserMessage.NO_ID) {
			to = null; // TODO
		}

		CommandOrigin commandOrigin = CommandOrigin.XMPP;

		switch (commandOrigin) {
		case XMPP:
			mXMPPService.send(userMsg.geMessage(), to);
			break;
		default:
			break;
		}

	}
}
