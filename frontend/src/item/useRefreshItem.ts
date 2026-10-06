import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, type ItemDetails, type ItemRef } from "@/api/client";
import { toast } from "@/components/ui/toast";
import { applyDetails } from "./lists";

/**
 * Downloads the page of one item now. The dialog gets the new details at once, and the card is
 * replaced in place in every loaded list (its cover, facets and prediction may change).
 */
export function useRefreshItem(ref: ItemRef) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<ItemDetails> => {
      const result = await api.POST("/api/items/{source}/{item}/refresh", { params: { path: { source: ref.source, item: ref.item } } });
      if (!result.data) {
        const status = result.response.status;
        throw new Error(status === 502 ? "сайт не отдал страницу" : status === 409 ? "этот источник не умеет скачивать страницы" : `HTTP ${status}`);
      }
      return result.data;
    },
    onSuccess: (details) => applyDetails(queryClient, details, ref),
    onError: (error) => toast.add({ title: "Страница не загрузилась", description: error.message, type: "error" }),
  });
}
