package org.l2jmobius.gameserver.managers.world;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.zaxxer.hikari.HikariDataSource;
import org.l2jmobius.commons.database.DatabaseFactory;

/** Только отдельная временная MariaDB; имя схемы и порт жёстко ограничены. */
public final class X10WalletCheck
{
	public static void main(String[] args) throws Exception
	{
		final String url = System.getenv("X10_CHECK_DB_URL");
		if ((url == null) || !url.startsWith("jdbc:mysql://127.0.0.1:33307/l2hub_x10_check?"))
		{
			throw new IllegalArgumentException("Only the isolated X10 check database is allowed");
		}
		try (HikariDataSource pool = new HikariDataSource())
		{
			pool.setJdbcUrl(url);
			pool.setUsername("root");
			pool.setPassword(System.getenv("X10_CHECK_DB_PASSWORD"));
			pool.setMaximumPoolSize(8);
			final Field field = DatabaseFactory.class.getDeclaredField("DATABASE_POOL");
			field.setAccessible(true);
			field.set(null, pool);
			try (Connection con = pool.getConnection())
			{
				try (var st = con.createStatement())
				{
					st.executeUpdate("CREATE TABLE characters(charId INT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=InnoDB");
					st.executeUpdate("INSERT INTO characters VALUES(1),(2)");
				}
				X10Economy.createTables(con);
			}
			try (var executor = Executors.newFixedThreadPool(8))
			{
				final var credits = new ArrayList<Callable<Integer>>();
				for (int n = 0; n < 40; n++)
				{
					credits.add(() -> X10Economy.change(1, 100, 100, "event:one", false));
				}
				int accepted = 0;
				for (var future : executor.invokeAll(credits))
				{
					final int result = future.get();
					if (result < 0)
					{
						throw new AssertionError("Credit transaction failed");
					}
					accepted += result;
				}
				if (accepted != 1)
				{
					throw new AssertionError("Duplicate credit: " + accepted);
				}
				final var debits = new ArrayList<Callable<Integer>>();
				for (int n = 0; n < 20; n++)
				{
					final int id = n;
					debits.add(() -> X10Economy.change(1, -15, 0, "buy:" + id, true));
				}
				accepted = 0;
				for (var future : executor.invokeAll(debits))
				{
					final int result = future.get();
					if (result < 0)
					{
						throw new AssertionError("Debit transaction failed");
					}
					accepted += result;
				}
				if (accepted != 6)
				{
					throw new AssertionError("Overspend: " + accepted);
				}
			}
			try (Connection con = pool.getConnection(); var st = con.createStatement(); var rs = st.executeQuery("SELECT coins,style,(SELECT COUNT(*) FROM l2hub_x10_ledger) AS entries FROM l2hub_x10_wallet WHERE char_id=1"))
			{
				if (!rs.next() || (rs.getLong(1) != 10) || (rs.getLong(2) != 100) || (rs.getInt(3) != 7))
				{
					throw new AssertionError("Ledger, rollback or balance mismatch");
				}
			}
			if (X10Economy.change(1, 100, 100, "event:one", false) != 0)
			{
				throw new AssertionError("Credit repeated after reloading history");
			}
			if ((X10Economy.change(1, 0, 0, "zero", false) != 1) || (X10Economy.change(1, 0, 0, "zero", false) != 0))
			{
				throw new AssertionError("Zero reward is not recorded idempotently");
			}
			for (int n = 0; n < 6; n++)
			{
				if (X10Economy.change(1, 0, -15, "style:" + n, true) != 1) { throw new AssertionError("Style purchase rejected"); }
			}
			if (X10Economy.change(1, 0, -15, "style:overspend", true) != 0) { throw new AssertionError("Style overspend accepted"); }
			try (Connection con = pool.getConnection(); var st = con.createStatement(); var rs = st.executeQuery("SELECT coins,style FROM l2hub_x10_wallet WHERE char_id=1"))
			{
				if (!rs.next() || rs.getLong(1) != 10 || rs.getLong(2) != 10) { throw new AssertionError("Currency tabs spent the other balance"); }
			}
			for (String day : new String[] {"2026-11-01", "2026-11-02", "2026-11-08", "2026-11-09", "2026-12-28", "2027-01-03"})
			{
				X10Economy.change(2, 20, 0, "daily:" + day + ":hunt", false);
			}
			X10Economy.change(2, 100, 1, "event:2026-11-07:one", false);
			if ((X10Economy.claims(2, "2026-11-02", "daily:") != 2) || (X10Economy.claims(2, "2026-11-02", "event:") != 1) || (X10Economy.claims(2, "2026-12-28", "daily:") != 2))
			{
				throw new AssertionError("Weekly claims or year boundary mismatch");
			}
			try (Connection con = pool.getConnection(); var st = con.createStatement())
			{
				st.executeUpdate("DELETE FROM characters WHERE charId=1");
				try (var rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM l2hub_x10_wallet WHERE char_id=1)+(SELECT COUNT(*) FROM l2hub_x10_ledger WHERE char_id=1)"))
				{
					if (!rs.next() || rs.getInt(1) != 0)
					{
						throw new AssertionError("Deleted character retained a wallet or reward history");
					}
				}
			}
			System.out.println("X10 wallet: concurrency, rollback, replay, zero rewards, weekly boundaries and character deletion passed");
		}
	}
}
