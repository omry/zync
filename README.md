## Zync (C) 2009 

Zync is a Java program that combines the power of rsync and zfs snapshots to backup remote servers. Configuration is very easy, and once configured, all you need to do is to add it to cron to be executed as often as you would like.
When Zync is executed, it performs rsync syncrhonzation of the specified servers, and when it's done it takes a ZFS snapshot of the current state. snapshots contains a timestamp for easy recognition:

```
$ ls -1 /storage/backup/.zfs/snapshot/
2009_08_08__16_13_29_IDT
2009_08_08__16_13_34_IDT
2009_08_08__16_13_47_IDT
```
Zync can automatically delete snapshots older than a particular time (older than X days, hours or minutes). this ensures that your backup directory does not grows indefinitely.

### Recent changes

Changes in last version:
- Now runs rsync in multiple threads, improving performance when backing up multiple servers
- Now saves stdout and stderr to log files specified as zync.rsync.logs_dir or zync.backup.logs_dir.
- Fixed to ignore rsync exit code 24 (file vanished) and to finish operating even if one of the backups failed.

[Full change log](/CHANGELOG).

### Configuration
Zync comes with two sample configuration files:

- [sample.conf](/sample.conf) : an annotated sample with all the options documents.
- [minimal-sample.conf](/minimal-sample.conf) : a minimalistic sample that can be used for a quick start
Copy one of those files to backup.conf (or any other name you choose) and edit it to suite your needs.

Here is a minimalistic example of a backup configuration file, You can have multiple backup blocks to backup more than one server.

```
@swush 1.0

zync
{
	backup
	{
		host : root@server.com
		directory : /
		exclude
		{
			/cdrom
			/dev/
			/media
			/proc
			/sys
			/tmp
			/var/cache
			/var/log
			/var/lib/mysql
		}
	}
	
	rsync
	{
		destination : "/storage/backup/${host}"
	}
	
	zfs
	{
		backup_file_system : storage/backup
		
		snapshot
		{
			delete_older : 30d
		}		 
	}
}
```

Typically, you would want to setup the server that runs Zync so that it can access the backed-up machines without having to type a password. You can find info about it online but the gist of it is:
- Create a key
- Append the created public key (e.gh ssh/id_dsa.pub) to the target machine ~/.ssh/authorized_keys of the user you want the backup to connect with.

```bash
$ ssh-keygen -t dsa
$ cat ~/.ssh/id_dsa.pub | ssh root@OTHER_MACHINE "cat – >> ~/.ssh/authorized_keys"
```

### Running
```bash
$ java -jar zync-VER.jar [options]
```
Where options are:
- -v or --verbose : prints what zync is doing. by default verbose is off and only errors are printed.
- -f or --file : backup configuration file to use, by default uses `backup.conf`

### License
Zync is released under the [BSD license](/LICENSE].​

### Build instructions

Check out from repository, and build using ant (you need Build, jdk and ant installed):
```
$ ant
```
Generated files will be placed in a new build directory inside the project directory.
