import { useState } from "react";
import { useTranslation } from "react-i18next";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { RELATED, VERDICTS, type Verdict } from "./verdicts";

/**
 * ONE WORD ON EVERY PAIR LEFT ON THE PAGE, under its last row: a button per answer, the same as a row's —
 * one work, different, a translation, a part, one series — asked to be confirmed with how many pairs it
 * takes, and then given pair by pair, as the presses of every row would ([answerAll]): a pair a word
 * before it answered already (two works said one, every pair of them is) is not asked again. While it
 * is at work, how many are given so far; each word in the journal, taken back where it stands.
 */
export function AnswerAll({
  left,
  locked,
  answerAll,
}: {
  /** How many pairs on the page have no answer. */
  left: number;
  /** The rows moving or a word on them all at work: nothing pressed. */
  locked: boolean;
  /** Gives the word to every pair left, telling how many are given so far. */
  answerAll: (verdict: Verdict, progress: (done: number) => void) => Promise<void>;
}) {
  const { t } = useTranslation("duplicate");
  const [asked, setAsked] = useState<{ verdict: Verdict; word: string } | null>(null);
  const [done, setDone] = useState<number | null>(null);
  const confirm = async () => {
    if (!asked) return;
    setDone(0);
    try {
      await answerAll(asked.verdict, setDone);
      setAsked(null);
    } finally {
      setDone(null);
    }
  };
  return (
    <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-dashed p-3 text-xs text-muted-foreground">
      <span className="mr-1">{t("AnswerAll.label", { count: left })}</span>
      {[...VERDICTS, ...RELATED].map(({ verdict, icon, word }) => (
        <Button key={verdict} size="sm" variant="outline" disabled={locked} title={t("AnswerAll.hint", { word: t(`PairRow.${word}`) })} onClick={() => setAsked({ verdict, word: t(`PairRow.${word}`) })}>
          {icon} {t(`PairRow.${word}`)}
        </Button>
      ))}
      <Dialog open={asked !== null} onOpenChange={(open) => !open && done === null && setAsked(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("AnswerAll.confirmTitle", { count: left, word: asked?.word })}</DialogTitle>
            <DialogDescription>{t("AnswerAll.confirmText")}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {done !== null && <span className="mr-auto self-center text-sm text-muted-foreground tabular-nums">{t("AnswerAll.progress", { done, count: left })}</span>}
            <Button variant="outline" disabled={done !== null} onClick={() => setAsked(null)}>
              {t("AnswerAll.cancel")}
            </Button>
            <AsyncButton icon={[...VERDICTS, ...RELATED].find((v) => v.verdict === asked?.verdict)?.icon} onClick={confirm}>
              {t("AnswerAll.confirm", { word: asked?.word })}
            </AsyncButton>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
