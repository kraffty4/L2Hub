package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * L2Hub: маршрут охотника ногами по геодате (docs/PHANTOMS.md, раздел 4.25).<br>
 * Поиск пути Mobius ({@code PathFinding}) — A* по клеткам 16×16 с потолком 7000 шагов: на отрезке до 1500 он находит обход дома, но не длинной стены,
 * обрыва или изгиба реки, и охотник упирался в них по дороге на спот. Здесь — свой A*, грубее: узлы сетки через {@link #STEP}, ребро — прямая,
 * которую разрешает геодата ({@code canMoveToTarget}), в окне вокруг старта и цели. Готовый путь сглажен по прямой видимости в отрезки до
 * {@link #SEGMENT}: каждый Mobius проходит прямо, без своего поиска.
 */
public final class PhantomPath
{
	/** Шаг сетки: 6 клеток геодаты — проход шире него найдётся. */
	private static final int STEP = 96;
	/** Окно поиска: прямоугольник старта и цели плюс столько с каждой стороны — обход стены уходит вбок. */
	private static final int MARGIN = 3000;
	/** Потолок раскрытых узлов: путь не нашёлся — null, охотник идёт по-старому (повороты). */
	private static final int MAX_EXPANDED = 30000;
	/** Отрезок пути не длиннее: дальше 3000 Mobius ведёт игрока без геодаты. */
	private static final int SEGMENT = 1500;
	/** Цель ближе стольких шагов сетки и видна прямо — путь найден. */
	private static final int GOAL_STEPS = 2;
	/** Сводка цены поиска в лог (раздел 4.30) — раз в столько: по ней решать, выносить ли поиск с общего тика охотников. */
	private static final long STATS_INTERVAL = 600000;

	private static final Logger LOGGER = Logger.getLogger(PhantomPath.class.getName());
	private static int _calls;
	private static int _failed;
	private static long _totalNanos;
	private static long _maxNanos;
	private static long _statsSince = System.currentTimeMillis();

	private PhantomPath()
	{
	}

	/**
	 * Как {@link #find(int, int, int, int, int, int, int, int)}, со сводкой цены: раз в {@link #STATS_INTERVAL} — сколько маршрутов, сколько без пути, среднее и худшее время.
	 */
	public static List<Location> find(int sx, int sy, int sz, int tx, int ty, int tz, int reach, int instanceId)
	{
		final long start = System.nanoTime();
		final List<Location> path = search(sx, sy, sz, tx, ty, tz, reach, instanceId);
		final long nanos = System.nanoTime() - start;
		synchronized (PhantomPath.class)
		{
			_calls++;
			_failed += path == null ? 1 : 0;
			_totalNanos += nanos;
			_maxNanos = Math.max(_maxNanos, nanos);
			final long now = System.currentTimeMillis();
			if ((now - _statsSince) >= STATS_INTERVAL)
			{
				LOGGER.info(PhantomPath.class.getSimpleName() + ": " + _calls + " routes in " + ((now - _statsSince) / 60000) + " min, no way " + _failed + ", avg " + String.format(Locale.ROOT, "%.1f", _totalNanos / 1e6 / _calls) + " ms, max " + String.format(Locale.ROOT, "%.1f", _maxNanos / 1e6) + " ms.");
				_calls = 0;
				_failed = 0;
				_totalNanos = 0;
				_maxNanos = 0;
				_statsSince = now;
			}
		}
		return path;
	}

	/**
	 * Поиск маршрута.
	 * @param reach дойти ближе этого к цели — уже на месте (охотнику — радиус спота): сама точка входа бывает в «кармане» геодаты, куда прямой нет
	 * @return точки пути от старта (без него) до цели или до места в {@code reach} от неё, каждая видна прямо из предыдущей; null — пути в окне нет
	 */
	private static List<Location> search(int sx, int sy, int sz, int tx, int ty, int tz, int reach, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int minI = (Math.min(sx, tx) - MARGIN - sx) / STEP;
		final int maxI = (Math.max(sx, tx) + MARGIN - sx) / STEP;
		final int minJ = (Math.min(sy, ty) - MARGIN - sy) / STEP;
		final int maxJ = (Math.max(sy, ty) + MARGIN - sy) / STEP;
		final int width = (maxI - minI) + 1;
		final int size = width * ((maxJ - minJ) + 1);
		final float[] cost = new float[size];
		Arrays.fill(cost, Float.MAX_VALUE);
		final int[] parent = new int[size];
		final int[] heights = new int[size];
		final boolean[] closed = new boolean[size];
		final PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
		final int start = (-minJ * width) - minI;
		cost[start] = 0;
		heights[start] = sz;
		parent[start] = -1;
		open.add(new float[] { (float) Math.hypot(tx - sx, ty - sy), start });
		int goal = -1;
		int expanded = 0;
		while (!open.isEmpty() && (expanded < MAX_EXPANDED))
		{
			final int node = (int) open.poll()[1];
			if (closed[node])
			{
				continue;
			}
			closed[node] = true;
			expanded++;
			final int ni = (node % width) + minI;
			final int nj = (node / width) + minJ;
			final int nx = sx + (ni * STEP);
			final int ny = sy + (nj * STEP);
			final int nz = heights[node];
			final double toTarget = Math.hypot(tx - nx, ty - ny);
			if ((toTarget <= reach) || ((toTarget <= (STEP * GOAL_STEPS)) && PhantomCompat.canMoveToTarget(geo, nx, ny, nz, tx, ty, tz, instanceId)))
			{
				goal = node;
				break;
			}
			for (int di = -1; di <= 1; di++)
			{
				for (int dj = -1; dj <= 1; dj++)
				{
					final int i = ni + di;
					final int j = nj + dj;
					if (((di == 0) && (dj == 0)) || (i < minI) || (i > maxI) || (j < minJ) || (j > maxJ))
					{
						continue;
					}
					final int next = ((j - minJ) * width) + (i - minI);
					final float nextCost = cost[node] + (float) (STEP * Math.hypot(di, dj));
					if (closed[next] || (nextCost >= cost[next]))
					{
						continue;
					}
					final int x = sx + (i * STEP);
					final int y = sy + (j * STEP);
					final int z = geo.getHeight(x, y, nz);
					if (!PhantomCompat.canMoveToTarget(geo, nx, ny, nz, x, y, z, instanceId))
					{
						continue;
					}
					cost[next] = nextCost;
					heights[next] = z;
					parent[next] = node;
					open.add(new float[] { nextCost + (float) Math.hypot(tx - x, ty - y), next });
				}
			}
		}
		if (goal < 0)
		{
			return null;
		}

		final List<Location> raw = new ArrayList<>();
		for (int node = goal; node >= 0; node = parent[node])
		{
			raw.add(0, new Location(sx + (((node % width) + minI) * STEP), sy + (((node / width) + minJ) * STEP), heights[node]));
		}
		// Сама цель — если видна прямо из последнего узла (или узел один — старт уже в reach).
		final Location last = raw.get(raw.size() - 1);
		if ((raw.size() < 2) || PhantomCompat.canMoveToTarget(geo, last.getX(), last.getY(), last.getZ(), tx, ty, tz, instanceId))
		{
			raw.add(new Location(tx, ty, tz));
		}
		// Сглаживание: из точки — к самой дальней, что видна прямо и не дальше SEGMENT.
		final List<Location> path = new ArrayList<>();
		int current = 0;
		while (current < (raw.size() - 1))
		{
			final Location from = raw.get(current);
			int best = current + 1;
			for (int k = raw.size() - 1; k > (current + 1); k--)
			{
				final Location to = raw.get(k);
				if ((Math.hypot(to.getX() - from.getX(), to.getY() - from.getY()) <= SEGMENT) && PhantomCompat.canMoveToTarget(geo, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), instanceId))
				{
					best = k;
					break;
				}
			}
			path.add(raw.get(best));
			current = best;
		}
		return path;
	}
}
