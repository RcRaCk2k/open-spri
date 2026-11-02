import { Button, Group, Modal, Select, Stack, Table, Text, TextInput } from "@mantine/core";
import { useState } from "react";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { formatDate } from "../../format";

export interface Field<T> {
  key: keyof T & string;
  label: string;
  required?: boolean;
  type?: "text" | "date" | "role";
  mono?: boolean;
}

const ROLE_OPTIONS = [
  { value: "AUFTRAGGEBER", label: "Auftraggeber (wir bestellen)" },
  { value: "LEISTUNGSERBRINGER", label: "Leistungserbringer (Partner bestellt)" },
];

/**
 * Table with create, edit and delete for the agreements of a partner (framework contracts, powers of attorney,
 * projects). The API checks every entry; its errors are shown in the dialog.
 */
export function AgreementTable<T extends { id?: number | null }>({
  title, rows, fields, loading, error, editable, onSave, onDelete, saving, saveError, extra,
}: {
  title: string;
  rows: T[] | undefined;
  fields: Field<T>[];
  loading: boolean;
  error: unknown;
  editable: boolean;
  onSave: (row: T, done: () => void) => void;
  onDelete: (row: T) => void;
  saving: boolean;
  saveError: unknown;
  extra?: (row: T) => React.ReactNode;
}) {
  const [editing, setEditing] = useState<Record<string, string> | null>(null);
  const empty = () => Object.fromEntries(fields.map((f) => [f.key, f.type === "role" ? "AUFTRAGGEBER" : ""]));
  const toForm = (row: T) => Object.fromEntries(fields.map((f) => [f.key, String((row as Record<string, unknown>)[f.key] ?? "")]));
  const show = (row: T, f: Field<T>) => {
    const v = (row as Record<string, unknown>)[f.key];
    if (v === null || v === undefined || v === "") return "–";
    if (f.type === "date") return formatDate(String(v));
    if (f.type === "role") return v === "AUFTRAGGEBER" ? "AG" : "LE";
    return String(v);
  };
  return (
    <Stack>
      <Group justify="space-between">
        <Text fw={600}>{title}</Text>
        {editable && <Button size="xs" onClick={() => setEditing({ ...empty(), id: "" })}>Hinzufügen</Button>}
      </Group>
      <ErrorAlert error={error} />
      {loading ? <Loading /> : !rows || rows.length === 0 ? <Text size="sm" c="dimmed">Keine Einträge.</Text> : (
        <Table.ScrollContainer minWidth={600}>
          <Table>
            <Table.Thead><Table.Tr>{fields.map((f) => <Table.Th key={f.key}>{f.label}</Table.Th>)}<Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr key={row.id ?? JSON.stringify(row)}>
                  {fields.map((f) => <Table.Td key={f.key} className={f.mono ? "mono" : undefined}>{show(row, f)}</Table.Td>)}
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      {extra?.(row)}
                      {editable && <Button size="compact-xs" variant="subtle" onClick={() => setEditing({ ...toForm(row), id: String(row.id ?? "") })}>Ändern</Button>}
                      {editable && <Button size="compact-xs" variant="subtle" color="red" onClick={() => onDelete(row)}>Löschen</Button>}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={`${title}: ${editing?.id ? "ändern" : "hinzufügen"}`}>
        {editing && (
          <form onSubmit={(e) => {
            e.preventDefault();
            const row = Object.fromEntries(fields.map((f) => [f.key, editing[f.key]?.trim() ? editing[f.key]!.trim() : null]));
            onSave({ ...row, id: editing.id ? Number(editing.id) : null } as unknown as T, () => setEditing(null));
          }}>
            <Stack>
              {fields.map((f) => f.type === "role" ? (
                <Select key={f.key} label={f.label} data={ROLE_OPTIONS} allowDeselect={false} value={editing[f.key] ?? "AUFTRAGGEBER"} onChange={(v) => setEditing({ ...editing, [f.key]: v ?? "AUFTRAGGEBER" })} />
              ) : (
                <TextInput key={f.key} label={f.label} required={f.required} type={f.type === "date" ? "date" : "text"} value={editing[f.key] ?? ""}
                  onChange={(e) => { const v = e.currentTarget.value; setEditing({ ...editing, [f.key]: v }); }} />
              ))}
              <ErrorAlert error={saveError} />
              <Group justify="flex-end"><Button type="submit" loading={saving}>Speichern</Button></Group>
            </Stack>
          </form>
        )}
      </Modal>
    </Stack>
  );
}
