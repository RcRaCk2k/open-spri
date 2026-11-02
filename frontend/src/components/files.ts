/** Lets the browser save content that the API returned (WSDL, documents); nothing leaves the browser. */
export function saveFile(content: BlobPart, fileName: string, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** For binary request bodies: openapi-fetch would otherwise serialize them as JSON. */
export const rawBody = { bodySerializer: (body: unknown) => body as BodyInit };

/** One line per entry (CIDR, lists): empty lines and surrounding blanks are dropped. */
export function lines(value: string): string[] {
  return value.split(/[\n,;]+/).map((v) => v.trim()).filter((v) => v.length > 0);
}
