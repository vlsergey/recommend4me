import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/toast";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { App } from "./App";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <Toaster>
          {/* The last guard: what the parts below do not catch is said here, not a blank page */}
          <ErrorBoundary title="Приложение не смогло отрисоваться — обновите страницу">
            <App />
          </ErrorBoundary>
        </Toaster>
      </TooltipProvider>
    </QueryClientProvider>
  </StrictMode>,
);
