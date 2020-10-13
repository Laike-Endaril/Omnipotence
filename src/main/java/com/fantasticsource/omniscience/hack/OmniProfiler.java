package com.fantasticsource.omniscience.hack;

import com.fantasticsource.tools.ReflectionTool;
import com.fantasticsource.tools.Tools;
import com.google.common.collect.Lists;
import net.minecraft.profiler.Profiler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OmniProfiler extends Profiler
{
    protected static int prevGCRuns = 0;
    protected static final long NORMAL_TICK_TIME_NANOS = 50_000_000;
    protected static final Field
            PROFILER_PROFILING_SECTION_FIELD = ReflectionTool.getField(Profiler.class, "field_76323_d", "profilingSection"),
            PROFILER_SECTION_LIST_FIELD = ReflectionTool.getField(Profiler.class, "field_76325_b", "sectionList"),
            PROFILER_TIMESTAMP_LIST_FIELD = ReflectionTool.getField(Profiler.class, "field_76326_c", "timestampList"),
            PROFILER_PROFILING_MAP_FIELD = ReflectionTool.getField(Profiler.class, "field_76324_e", "profilingMap");

    protected List<String> sectionList;
    protected List<Long> timestampList;
    protected Map<String, Long> profilingMap;
    protected HashMap<String, Integer> gcMap = new HashMap<>();

    public OmniProfiler()
    {
        sectionList = (List<String>) ReflectionTool.get(PROFILER_SECTION_LIST_FIELD, this);
        timestampList = (List<Long>) ReflectionTool.get(PROFILER_TIMESTAMP_LIST_FIELD, this);
        profilingMap = (Map<String, Long>) ReflectionTool.get(PROFILER_PROFILING_MAP_FIELD, this);
    }

    @Override
    public void startSection(String name)
    {
        if (profilingEnabled)
        {
            super.startSection(name);

            prevGCRuns = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) prevGCRuns += gcBean.getCollectionCount();
        }
    }

    public void endSection()
    {
        if (profilingEnabled)
        {
            long i = System.nanoTime();
            long j = timestampList.remove(timestampList.size() - 1);
            sectionList.remove(sectionList.size() - 1);
            long k = i - j;

            String profilingSection = (String) ReflectionTool.get(PROFILER_PROFILING_SECTION_FIELD, this);

            if (profilingMap.containsKey(profilingSection))
            {
                profilingMap.put(profilingSection, profilingMap.get(profilingSection) + k);
            }
            else
            {
                profilingMap.put(profilingSection, k);
            }

            int gcRuns = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) gcRuns += gcBean.getCollectionCount();
            if (gcRuns > prevGCRuns)
            {
                if (gcMap.containsKey(profilingSection)) gcMap.put(profilingSection, gcRuns - prevGCRuns + gcMap.get(profilingSection));
                else gcMap.put(profilingSection, gcRuns - prevGCRuns);
                prevGCRuns = gcRuns;
            }

            ReflectionTool.set(PROFILER_PROFILING_SECTION_FIELD, this, sectionList.isEmpty() ? "" : sectionList.get(sectionList.size() - 1));
        }
    }


    public List<OmniProfiler.Result> getProfilingData(String sectionName, int tickSpan, int totalGCRuns, long totalGCNanos)
    {
        if (!profilingEnabled)
        {
            return Collections.emptyList();
        }
        else
        {
            long rootTime = profilingMap.getOrDefault("root", 0L);
            List<OmniProfiler.Result> list = Lists.newArrayList();

            if (!sectionName.isEmpty()) sectionName = sectionName + ".";

            long sectionTime = 0L;
            for (String s : profilingMap.keySet())
            {
                if (s.length() > sectionName.length() && s.startsWith(sectionName) && s.indexOf(".", sectionName.length() + 1) < 0)
                {
                    sectionTime += profilingMap.get(s);
                }
            }

            float subsectionTimeSum = sectionTime;
            sectionTime = Tools.max(sectionTime, profilingMap.getOrDefault(sectionName, 0L));

            if (rootTime < sectionTime) rootTime = sectionTime;

            for (String s1 : profilingMap.keySet())
            {
                if (s1.length() > sectionName.length() && s1.startsWith(sectionName) && s1.indexOf(".", sectionName.length() + 1) < 0)
                {
                    long l = profilingMap.get(s1);
                    double d0 = (double) l * 100 / (double) sectionTime;
                    double d1 = (double) l * 100 / (double) rootTime;
                    double d2 = (double) l * 100 / (double) NORMAL_TICK_TIME_NANOS / (double) tickSpan;
                    String s2 = s1.substring(sectionName.length());
                    list.add(new OmniProfiler.Result(s2, d0, d1, d2, gcMap.getOrDefault(s1, 0)));
                }
            }

            for (String s3 : profilingMap.keySet())
            {
                profilingMap.put(s3, profilingMap.get(s3) * 999L / 1000L);
            }

            if ((float) sectionTime > subsectionTimeSum)
            {
                list.add(new OmniProfiler.Result("unspecified", (double) ((float) sectionTime - subsectionTimeSum) * 100 / (double) sectionTime, (double) ((float) sectionTime - subsectionTimeSum) * 100 / (double) rootTime, (double) ((float) sectionTime - subsectionTimeSum) * 100 / (double) NORMAL_TICK_TIME_NANOS / (double) tickSpan, 0));
            }

            if (sectionName.equals("root.")) list.add(new OmniProfiler.Result("GC", (double) totalGCNanos * 100 / (double) sectionTime, (double) totalGCNanos * 100 / (double) rootTime, (double) totalGCNanos * 100 / (double) NORMAL_TICK_TIME_NANOS / (double) tickSpan, totalGCRuns));

            Collections.sort(list);
            list.add(0, new OmniProfiler.Result(sectionName, 100, (double) sectionTime * 100 / (double) rootTime, (double) sectionTime * 100 / (double) NORMAL_TICK_TIME_NANOS / (double) tickSpan, gcMap.getOrDefault(sectionName, 0)));
            return list;
        }
    }


    public static final class Result implements Comparable<Result>
    {
        public String profilerName;
        public double usePercentage, totalUsePercentage, tickUsePercentage;
        public int gcRuns;

        public Result(String profilerName, double usePercentage, double totalUsePercentage, double tickUsePercentage, int gcRuns)
        {
            this.profilerName = profilerName;
            this.usePercentage = usePercentage;
            this.totalUsePercentage = totalUsePercentage;
            this.tickUsePercentage = tickUsePercentage;
            this.gcRuns = gcRuns;
        }

        public int compareTo(Result p_compareTo_1_)
        {
            if (p_compareTo_1_.usePercentage < usePercentage) return -1;
            if (p_compareTo_1_.usePercentage > usePercentage) return 1;
            return p_compareTo_1_.profilerName.compareTo(profilerName);
        }

        @SideOnly(Side.CLIENT)
        public int getColor()
        {
            return (profilerName.hashCode() & 11184810) + 4473924;
        }
    }
}