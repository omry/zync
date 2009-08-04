package net.firefang.zync;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import net.firefang.swush.Swush;

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
				rs.copyDirs(dirs);
				
				rs.execute();
				
				
			}
		}
		else
		{
			System.err.println("no backup elements defined in " + file);
		}
	}
}


class Rsync
{
	private final String m_rsync;
	private String m_host = "";
	private List<String> m_dirs = new ArrayList<String>();
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

		String dst = replaceWord(m_dest , "${host}", m_host);
		commands.add(dst);
		
        ProcessBuilder pb = new ProcessBuilder(commands);
        if (m_verbose)
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
        	System.out.println(cmd);
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
	private final PrintStream m_out;

	public InputStreamSucker(InputStream in, PrintStream out)
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
            	m_out.print((char)c);
            }
        }
        catch (IOException e)
        {
        	e.printStackTrace();
        }
    }
}