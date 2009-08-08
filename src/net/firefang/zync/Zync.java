package net.firefang.zync;


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import net.firefang.swush.Swush;
/**
 * @author omry
 * TODO: 
 * zfs snapshot retention policy
 */
public class Zync
{
	public static void main(String[] args) throws Exception
	{
		CmdLineParser p = new CmdLineParser();
		p.addStringOption("file");
		p.addBooleanOption('v', "verbose");
		p.parse(args);
		
		File file = new File((String)p.getOptionValue("file", "backup.conf"));
		boolean verbose = (Boolean)p.getOptionValue("verbose", false);
		
		
		Swush conf = new Swush(file);
		
		
		snapshot(verbose, conf);
		if (true)
		{
			return;
		}
		
		String rsync = conf.selectProperty("zync.rsync");
		String options[] = conf.selectFirst("zync.options").asArray();
		String globalDestination = conf.selectProperty("zync.destination");
		List<Swush> backups = conf.select("zync.backup");
		if (backups.size() > 0)
		{
			for(Swush backup : backups)
			{
				Rsync rs = new Rsync(rsync);
				rs.setVerbose(verbose);
				rs.setDest(backup.selectProperty("backup.destination", globalDestination));
				Swush optOverride = backup.selectFirst("backup.options");
				if (optOverride != null)
				{
					options = optOverride.asArray();
				}
				rs.setOptions(options);
				
				rs.setHost(backup.selectProperty("backup.host"));
				
				List<String> dirs = new ArrayList<String>();
				for(Swush dir : backup.select("backup.directory"))
				{
					if (dir.isPair())
						dirs.add(dir.getTextValue());
					else
					{
						String[] ar = dir.asArray();
						for(String s : ar) 
							dirs.add(s);
					}
				}
				
				List<String> excludes = new ArrayList<String>();
				for(Swush exclude : backup.select("backup.exclude"))
				{
					excludes.add(exclude.getTextValue());
				}
				
				rs.setExcludes(excludes);
				rs.copyDirs(dirs);
				
				rs.execute();
			}
		}
		else
		{
			System.err.println("no backup elements defined in " + file);
		}
	}

	private static void snapshot(boolean verbose, Swush conf) throws IOException, InterruptedException
	{
		String zfs = conf.selectProperty("zync.zfs.zfs", "/usr/sbin/zfs");
		String zfsfs = conf.selectProperty("zync.zfs.backup_file_system");
		DateFormat df = new SimpleDateFormat(conf.selectProperty("zync.zfs.snapshot_name_pattern", "yyyy_MM_dd__kk_mm_ss_zzz"));
		String timestamp = df.format(new Date());
		
		List<String> c = new ArrayList<String>();
		c.add(zfs);
		c.add("snapshot");
		c.add(zfsfs + "@" + timestamp);
		
		runProcess(c, System.out, verbose);
        
        String deleteOlder = conf.selectProperty("zync.zfs.delete_older");
        if (deleteOlder != null)
        {
        	long olderThen = 0;
        	
        	char u = deleteOlder.charAt(deleteOlder.length());
        	float f = Float.parseFloat(deleteOlder.substring(0, deleteOlder.length() - 1));
        	String msg;
        	switch(u)
        	{
        	case 'm':
        		olderThen = (long)(f * 1000 * 60);
        		msg = "older than " + f + " minutes";
        		break;
        	case 'h':
        		olderThen = (long)(f * 1000 * 60 * 60);
        		msg = "older than " + f + " hours";
        		break;
        	case 'd':
        		olderThen = (long)(f * 1000 * 60 * 60 * 24);
        		msg = "older than " + f + " days";
        		break;
        	default:
        		throw new RuntimeException("Unsupported unit type " + u + ", supported units are m : minute | h : hour | d : day");
        	}
        	
        	if (verbose)
        	{
        		System.out.println("Deleting snapshots " + msg);
        	}
        	
        	Map<String, Long> creation = getCreationTimes(zfs, zfsfs, verbose);
        	
        }
	}
	
	
	private static Map<String, Long> getCreationTimes(String zfs, String zfsfs, boolean verbose) throws IOException, InterruptedException
	{
		//zfs list -o name,creation  -rHt snapshot storage/backup
		
		List<String> c = new ArrayList<String>();
		c.add(zfs);
		c.add("list");
		c.add("-o");
		c.add("name,creation");
		c.add("-rHt");
		c.add("snapshot");
		c.add(zfsfs);
		
		
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		runProcess(c, bout, verbose);
        
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bout.toByteArray())));
        String line;
        
        while ((line = br.readLine()) != null)
        {
        	System.out.println(line);
        }
        
		return null;
	}

	static String toString(List<String> commands)
	{
		String cmd = "";
		for(String c : commands) 
		{
			if (cmd.length() == 0)
			{
				cmd = c;
			}
			else
				cmd += " " + c;
		}
		return cmd;
	}
	
	
	public static void runProcess(List<String> commands, OutputStream out, boolean verbose) throws InterruptedException, IOException
	{
		if (verbose)
		{
			System.out.println(toString(commands));
		}

		ProcessBuilder pb = new ProcessBuilder(commands);
		
        Process process = pb.start();
        InputStreamSucker stdout = new InputStreamSucker(process.getInputStream(), out);
        InputStreamSucker stderr = new InputStreamSucker(process.getErrorStream(), System.err);

        process.waitFor();
        stdout.join();
        stderr.join();
        
        int exit = process.exitValue();
        if (exit != 0)
        {
        	System.err.println("Exit code " + exit + " from : " + toString(commands));
        	System.exit(exit);
        }
	}
}


class Rsync
{
	private final String m_rsync;
	private String m_host = "";
	private List<String> m_dirs = new ArrayList<String>();
	private List<String> m_excludes = new ArrayList<String>();
	private String m_dest  = "";
	private String m_options[];
	private boolean m_verbose;

	public Rsync(String rsync)
	{
		m_rsync = rsync;
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
	
	
	public void execute() throws InterruptedException, IOException
	{
		List<String> commands = new ArrayList<String>();
		commands.add(m_rsync);
		if (m_verbose) commands.add("-v");
		for(String opt : m_options) commands.add(opt);
		for(String dir : m_dirs)
		{
			commands.add (m_host + ":" + dir);
		}
		
		for(String exclude : m_excludes)
		{
			commands.add ("--exclude=" + exclude);
		}
		

		String dst = replaceWord(m_dest , "${host}", m_host);
		commands.add(dst);
		new File(dst).mkdirs();
		
        ProcessBuilder pb = new ProcessBuilder(commands);
        if (m_verbose)
        {
        	System.out.println(Zync.toString(commands));
        }
        Process process = pb.start();
        InputStreamSucker stdout = new InputStreamSucker(process.getInputStream(), System.out);
        InputStreamSucker stderr = new InputStreamSucker(process.getErrorStream(), System.err);

        process.waitFor();
        stdout.join();
        stderr.join();
        
        int exit = process.exitValue();
        if (exit != 0)
        	System.exit(exit);
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
            	m_out.write((char)c);
            }
        }
        catch (IOException e)
        {
        	e.printStackTrace();
        }
    }
}