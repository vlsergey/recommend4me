import { useEffect } from "react";
import { MonitorIcon, MoonIcon, SunIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from "@/components/ui/dropdown-menu";
import { usePref } from "@/lib/usePref";

export type Theme = "light" | "dark" | "system";

const THEMES: Theme[] = ["light", "dark", "system"];

function apply(theme: Theme) {
  const dark = theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
}

/** The theme chosen, applied to the page: held above the menu, whose entries are there only while it is open. */
export function useTheme() {
  const [theme, setTheme] = usePref<Theme>("theme", "system");

  useEffect(() => {
    apply(theme);
    if (theme !== "system") return;
    // "As in the system" follows the system when it changes, not only at load
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => apply("system");
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [theme]);

  return [theme, setTheme] as const;
}

/** The theme as a submenu of the settings in the menu ([ScreenBar][@/screen/ScreenNav]). */
export function ThemeSubmenu({ theme, onChange }: { theme: Theme; onChange: (theme: Theme) => void }) {
  const { t } = useTranslation();
  const Icon = theme === "light" ? SunIcon : theme === "dark" ? MoonIcon : MonitorIcon;
  return (
    <DropdownMenuSub>
      {/* Opened by a press alone: on a touch screen there is no hover, and a hover opening it first makes the press close it */}
      <DropdownMenuSubTrigger openOnHover={false}>
        <Icon /> {t("Theme.title")}
        <span className="ms-2 text-muted-foreground">{t(`Theme.${theme}`)}</span>
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent>
        <DropdownMenuRadioGroup value={theme} onValueChange={(v) => onChange(v as Theme)}>
          {THEMES.map((th) => (
            <DropdownMenuRadioItem key={th} value={th}>
              {t(`Theme.${th}`)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}
