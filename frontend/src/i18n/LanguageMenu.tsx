import { LanguagesIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from "@/components/ui/dropdown-menu";
import { currentLocale, setLocale } from "./i18n";
import { isLocale, LOCALE_NAMES, LOCALES } from "./locales";

/** The languages to choose from, each by its own name, the current one marked. */
function Choices() {
  return (
    <DropdownMenuRadioGroup
      value={currentLocale()}
      onValueChange={(v) => {
        const locale = String(v);
        if (isLocale(locale)) setLocale(locale);
      }}
    >
      {LOCALES.map((l) => (
        <DropdownMenuRadioItem key={l} value={l} lang={l}>
          {LOCALE_NAMES[l]}
        </DropdownMenuRadioItem>
      ))}
    </DropdownMenuRadioGroup>
  );
}

/** The language of the interface as a submenu of the settings in the menu ([ScreenBar][@/screen/ScreenNav]). */
export function LanguageSubmenu() {
  const { t } = useTranslation();
  return (
    <DropdownMenuSub>
      {/* Opened by a press alone: on a touch screen there is no hover, and a hover opening it first makes the press close it */}
      <DropdownMenuSubTrigger openOnHover={false}>
        <LanguagesIcon /> {t("Language.title")}
        <span className="ms-2 text-muted-foreground">{LOCALE_NAMES[currentLocale()]}</span>
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent>
        <Choices />
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}
