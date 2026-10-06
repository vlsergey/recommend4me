import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type JobStatus, type SourceMode } from "@/api/client";

export const ACTIVE = new Set<JobStatus["state"]>(["RUNNING", "CANCELLING"]);

export function isActive(job: JobStatus | undefined): boolean {
  return job !== undefined && ACTIVE.has(job.state);
}

/** The jobs of every scraping source: fast while one runs, otherwise now and then. */
export function useJobs() {
  return useQuery({
    queryKey: ["jobs"],
    queryFn: async () => unwrap(await api.GET("/api/jobs")),
    refetchInterval: (q) => (q.state.data?.some(isActive) ? 1000 : 30000),
  });
}

function putJob(jobs: JobStatus[] | undefined, job: JobStatus): JobStatus[] {
  return [...(jobs ?? []).filter((j) => j.source !== job.source), job];
}

export function useStartJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ source, mode }: { source: string; mode: SourceMode }) => {
      const result = await api.POST("/api/sources/{source}/job", { params: { path: { source } }, body: { mode } });
      // 409: one runs already, or the source has no such mode — the answer is the job as it is
      const job = result.data ?? (result.error as JobStatus | undefined);
      if (!job) throw new Error(`HTTP ${result.response.status}`);
      return job;
    },
    onSuccess: (job) => queryClient.setQueryData<JobStatus[]>(["jobs"], (jobs) => putJob(jobs, job)),
  });
}

export function useCancelJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (source: string) => unwrap(await api.DELETE("/api/sources/{source}/job", { params: { path: { source } } })),
    onSuccess: (job) => queryClient.setQueryData<JobStatus[]>(["jobs"], (jobs) => putJob(jobs, job)),
  });
}
