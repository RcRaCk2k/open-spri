import { Badge } from "@mantine/core";
import { PHASE_COLORS, phaseLabel } from "../pages/orders/labels";

export function PhaseBadge({ phase }: { phase: string }) {
  return <Badge color={PHASE_COLORS[phase] ?? "gray"} variant="light">{phaseLabel(phase)}</Badge>;
}
