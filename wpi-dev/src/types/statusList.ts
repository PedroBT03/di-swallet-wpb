export interface StatusListJsonPayload {
  id: string;
  type: string;
  statusPurpose: string;
  encodedList: string;
  capacity: number;
  allocated: number;
  issuedAt: string;
}

export interface StatusListEntry {
  listId: string;
  index: number;
  statusPurpose: string;
  status: "ACTIVE" | "REVOKED";
  revoked: boolean;
}

/** Canonical WPB status list id (matches StatusListService). */
export const DEFAULT_STATUS_LIST_ID = "PRIMARY_LIST";
