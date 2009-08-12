package net.firefang.zync;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.firefang.swush.Swush;

/**
 * @author omry
 */
public class Zync
{
	public static void main(String[] args) throws Exception
	{
		CmdLineParser p = new CmdLineParser();
		p.addStringOption('f', "file");
		p.addBooleanOption('v', "verbose");
		p.parse(args);

		File file = new File((String) p.getOptionValue("file", "backup.conf"));
		boolean verbose = (Boolean) p.getOptionValue("verbose", false);

		Swush conf = new Swush(file);

		String rsync = conf.selectProperty("zync.rsync.command","/usr/bin/rsync");
		String options[] = conf.selectFirst("zync.rsync.options").asArray();
		if (options.length == 0) // no options specified, use default options
		{
			options = new String[]{"-a", "--force", "--delete-excluded", "--delete","--inplace"};
		}
		
		Set<Integer> ignoreExitCodes = new HashSet<Integer>();
		Swush ig = conf.selectFirst("zync.rsync.ignore_exit_codes");
		if (ig != null)
		{
			for(String s : ig.asArray())
				ignoreExitCodes.add(Integer.parseInt(s));
		}
		else
		{
			ignoreExitCodes.add(23); // by default, ignore 'file vanished' exit code.
		}
		
		String globalDestination = conf.selectProperty("zync.rsync.destination");
		List<Swush> backups = conf.select("zync.backup");
		
		int failedExitCode = 0;
		
		if (backups.size() > 0)
		{
			for (Swush backup : backups)
			{
				Rsync rs = new Rsync(rsync);
				rs.setVerbose(verbose);
				rs.setDest(backup.selectProperty("backup.destination",globalDestination));
				Swush optOverride = backup.selectFirst("backup.options");
				if (optOverride != null)
				{
					options = optOverride.asArray();
				}
				rs.setOptions(options);

				rs.setHost(backup.selectProperty("backup.host"));

				List<String> dirs = new ArrayList<String>();
				for (Swush dir : backup.select("backup.directory"))
				{
					if (dir.isPair())
						dirs.add(dir.getTextValue());
					else
					{
						String[] ar = dir.asArray();
						for (String s : ar)
							dirs.add(s);
					}
				}

				List<String> excludes = new ArrayList<String>();
				for (Swush exclude : backup.select("backup.exclude"))
				{
					if (exclude.isPair())
						excludes.add(exclude.getTextValue());
					else
					{
						String[] ar = exclude.asArray();
						for (String s : ar)
							excludes.add(s);
					}
				}

				rs.setExcludes(excludes);
				rs.copyDirs(dirs);
				rs.setIgnoreExitCodes(ignoreExitCodes);

				int exit = rs.execute();
				if (failedExitCode  == 0 && exit != 0)
					failedExitCode  = exit;
			}

			snapshot(verbose, conf);
			
			if (failedExitCode  != 0)
			{
				System.err.println("Existing with error code " + failedExitCode);
				System.exit(failedExitCode );
			}
		} else
		{
			System.err.println("no backup elements defined in " + file);
		}
	}

	private static void snapshot(boolean verbose, Swush conf)
			throws IOException, InterruptedException, ParseException
	{
		String zfs = conf.selectProperty("zync.zfs.zfs", "/usr/sbin/zfs");
		String zfsfs = conf.selectProperty("zync.zfs.backup_file_system");
		DateFormat df = new SimpleDateFormat(conf.selectProperty(
				"zync.zfs.snapshot.name_pattern", "yyyy_MM_dd__kk_mm_ss_zzz"));
		String timestamp = df.format(new Date());

		List<String> c = new ArrayList<String>();
		c.add(zfs);
		c.add("snapshot");
		c.add(zfsfs + "@" + timestamp);

		runProcess(c, System.out, System.err, verbose);

		String deleteOlder = conf
				.selectProperty("zync.zfs.snapshot.delete_older");
		if (deleteOlder != null)
		{
			long olderThen = 0;

			char u = deleteOlder.charAt(deleteOlder.length() - 1);
			float f = Float.parseFloat(deleteOlder.substring(0, deleteOlder
					.length() - 1));
			String msg;
			switch (u)
			{
			case 'm':
				olderThen = (long) (f * 1000 * 60);
				msg = "older than " + f + " minutes";
				break;
			case 'h':
				olderThen = (long) (f * 1000 * 60 * 60);
				msg = "older than " + f + " hours";
				break;
			case 'd':
				olderThen = (long) (f * 1000 * 60 * 60 * 24);
				msg = "older than " + f + " days";
				break;
			default:
				throw new RuntimeException(
						"Unsupported unit type "
								+ u
								+ ", supported units are m : minute | h : hour | d : day");
			}

			olderThen = System.currentTimeMillis() - olderThen;

			if (verbose)
			{
				System.out.println("Deleting snapshots " + msg);
			}

			Map<Long, String> creation = getCreationTimes(zfs, zfsfs, verbose);
			List<Long> times = new ArrayList<Long>(creation.keySet());
			Collections.sort(times);
			for (long l : times)
			{
				if (l < olderThen)
				{
					c = new ArrayList<String>();
					c.add(zfs);
					c.add("destroy");
					c.add(creation.get(l));
					runProcess(c, System.out,System.err, verbose);
				}
			}
		}
	}

	private static Map<Long, String> getCreationTimes(String zfs, String zfsfs,
			boolean verbose) throws IOException, InterruptedException,
			ParseException
	{
		// zfs list -o name,creation -rHt snapshot storage/backup

		List<String> c = new ArrayList<String>();
		c.add(zfs);
		c.add("list");
		c.add("-o");
		c.add("name,creation");
		c.add("-rHt");
		c.add("snapshot");
		c.add(zfsfs);

		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		runProcess(c, bout, System.err, false);

		BufferedReader br = new BufferedReader(new InputStreamReader(
				new ByteArrayInputStream(bout.toByteArray())));
		String line;
		// Sat Aug 8 13:52 2009
		SimpleDateFormat df = new SimpleDateFormat("EEE MMM dd kk:mm yyyy");

		Map<Long, String> m = new HashMap<Long, String>();
		while ((line = br.readLine()) != null)
		{
			int i = line.indexOf('\t');
			String name = line.substring(0, i);
			String date = line.substring(i + 1);
			Date d = df.parse(date);
			m.put(d.getTime(), name);
		}

		return m;
	}

	static String toString(List<String> commands)
	{
		String cmd = "";
		for (String c : commands)
		{
			if (cmd.length() == 0)
			{
				cmd = c;
			} else
				cmd += " " + c;
		}
		return cmd;
	}

	public static int runProcess(List<String> commands, String fout, String ferr, boolean verbose) throws InterruptedException, IOException
	{
		FileOutputStream stdout = null;
		FileOutputStream stderr = null;
		try
		{
			stdout = new FileOutputStream(fout);
			stderr = new FileOutputStream(ferr);
			return runProcess(commands, stdout, stderr, verbose);
			
		}finally
		{
			if (stdout != null) stdout.close();
			if (stderr != null) stderr.close();
		}
	}
	
	public static int runProcess(List<String> commands, OutputStream stdout, OutputStream stderr, boolean verbose) throws InterruptedException, IOException
	{
		if (verbose)
		{
			String s = toString(commands);
			System.out.println(s);
			stdout.write(("ZYNC : " + s+ "\n").getBytes());
		}

		ProcessBuilder pb = new ProcessBuilder(commands);

		Process process = pb.start();
		InputStreamSucker stdout1 = new InputStreamSucker(process.getInputStream(), stdout);
		InputStreamSucker stderr1 = new InputStreamSucker(process.getErrorStream(), stderr);

		process.waitFor();
		stdout1.join();
		stderr1.join();

		return process.exitValue();
	}
}

class Rsync
{
	private final String m_rsync;

	private String m_host = "";

	private List<String> m_dirs = new ArrayList<String>();

	private List<String> m_excludes = new ArrayList<String>();

	private String m_dest = "";

	private String m_options[];

	private boolean m_verbose;

	private Set<Integer> m_ignoreExitCodes;

	public Rsync(String rsync)
	{
		m_rsync = rsync;
	}

	public void setIgnoreExitCodes(Set<Integer> ignoreExitCodes)
	{
		m_ignoreExitCodes = ignoreExitCodes;
	}

	public void setVerbose(boolean verbose)
	{
		m_verbose = verbose;
	}

	public void setOptions(String options[])
	{
		m_options = options;

	}

	public void setHost(String host)
	{
		m_host = host;
	}

	public void copyDirs(List<String> dirs)
	{
		m_dirs = dirs;
	}

	public void setExcludes(List<String> excludes)
	{
		m_excludes = excludes;
	}

	public void setDest(String dest)
	{
		m_dest = dest;
	}

	public int execute() throws InterruptedException, IOException
	{
		List<String> commands = new ArrayList<String>();
		commands.add(m_rsync);
		if (m_verbose)
			commands.add("-v");
		for (String opt : m_options)
			commands.add(opt);
		for (String dir : m_dirs)
		{
			commands.add(m_host + ":" + dir);
		}

		for (String exclude : m_excludes)
		{
			commands.add("--exclude=" + exclude);
		}

		String dst = replaceWord(m_dest, "${host}", m_host);
		commands.add(dst);
		new File(dst).mkdirs();
		
		File logsDir = new File(m_dest, "logs");
		logsDir.mkdirs();
		
		File stderr = new File(logsDir, replaceWord("stderr_${host}.log", "${host}", m_host));
		File stdout = new File(logsDir, replaceWord("stdout_${host}.log", "${host}", m_host));
		
		if (m_verbose)
		{
			System.err.println("Saving logs to " + logsDir.getAbsolutePath());
		}

		int exit = Zync.runProcess(commands, stdout.getAbsolutePath(),stderr.getAbsolutePath(), m_verbose);
		if (exit == 0 || m_ignoreExitCodes.contains(exit)) return 0;
		else return exit;
	}

	static String replaceWord(String original, String find, String replacement)
	{
		int i = original.indexOf(find);
		if (i < 0)
		{
			return original; // return original if 'find' is not in it.
		}

		String partBefore = original.substring(0, i);
		String partAfter = original.substring(i + find.length());

		return partBefore + replacement + partAfter;
	}
}

class InputStreamSucker extends Thread
{
	private final InputStream m_in;

	private final OutputStream m_out;

	public InputStreamSucker(InputStream in, OutputStream out)
	{
		m_in = in;
		m_out = out;
		start();
	}

	public void run()
	{
		try
		{
			int c;
			while ((c = m_in.read()) != -1)
			{
				m_out.write((char) c);
			}
		} catch (IOException e)
		{
			e.printStackTrace();
		}
	}
}
