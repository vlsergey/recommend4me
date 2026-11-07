import { cn } from "@/lib/utils"

/** Buttons joined into one control: shared borders, the inner corners square. */
function ButtonGroup({ className, ...props }: React.ComponentProps<"div">) {
  return (
    // biome-ignore lint/a11y/useSemanticElements: a fieldset brings the browser's own border, padding and min-width, which this shadcn layout does not have
    <div
      role="group"
      data-slot="button-group"
      className={cn(
        "flex w-fit items-stretch [&>*]:focus-visible:relative [&>*]:focus-visible:z-10 [&>*:not(:first-child)]:rounded-l-none [&>*:not(:first-child)]:border-l-0 [&>*:not(:last-child)]:rounded-r-none",
        className
      )}
      {...props}
    />
  )
}

export { ButtonGroup }
