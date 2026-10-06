import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ContentTypeInfo, type Grade, type ItemRef, type ItemSummary } from "@/api/client";
import { toast } from "@/components/ui/toast";
import { findSource, gradeLabel } from "@/contenttype/types";
import { applyGrade, itemKey, ITEMS } from "./lists";

type Rate = { summary: ItemSummary; grade: Grade | null };

async function send(ref: ItemRef, grade: Grade | null): Promise<ItemSummary> {
  const path = { source: ref.source, item: ref.item };
  return grade === null
    ? unwrap(await api.DELETE("/api/items/{source}/{item}/rating", { params: { path } }))
    : unwrap(await api.PUT("/api/items/{source}/{item}/rating", { params: { path }, body: { grade } }));
}

/**
 * Grading an item. Every cached list is updated in place: an item graded in the "unrated" view
 * leaves it at once, so the next one moves up under the cursor. A toast offers to undo.
 */
export function useRateItem() {
  const queryClient = useQueryClient();

  const apply = (updated: ItemSummary) => {
    applyGrade(queryClient, updated);
    const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), updated.source)?.type;
    queryClient.invalidateQueries({ queryKey: ["model", type?.id] });
    queryClient.invalidateQueries({ queryKey: itemKey(updated) });
  };

  return useMutation({
    mutationFn: ({ summary, grade }: Rate) => send(summary, grade),
    onSuccess: (updated, { summary }) => {
      apply(updated);
      const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), updated.source)?.type;
      const previous = (summary.grade as Grade | undefined) ?? null;
      toast.add({
        title: updated.grade ? (type ? gradeLabel(type, updated.grade as Grade) : String(updated.grade)) : "Оценка снята",
        description: `${summary.title} ${summary.version}`.trim(),
        timeout: 5000,
        actionProps: {
          children: "Отменить",
          onClick: async () => {
            apply(await send(summary, previous));
            // An item put back into a list it had left needs the list from the server
            queryClient.invalidateQueries({ queryKey: [ITEMS] });
          },
        },
      });
    },
    onError: (error) => toast.add({ title: "Не удалось сохранить оценку", description: String(error), type: "error" }),
  });
}
