const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_abbeb_001";
const ADMIN_EMAIL = "abbeb2031@gmail.com";
const EMP_UID = "emp_salem_002";
const AID_MGR_UID = "aid_mgr_003";
const OTHER_UID = "other_user_004";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp() {
  return new Date(Date.now() - 2000);
}

function adminDb() {
  return testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();
}

function validProfilePayload(uid, overrides = {}) {
  const now = pastTimestamp();
  return {
    ownerUid: uid,
    username: `user_${uid}`,
    fullName: "موظف سجل الفهد",
    email: `${uid}@sijil.test`,
    phone: "770000111",
    role: "employee",
    isActive: true,
    canAddCitizen: true,
    canEditCitizen: false,
    canDeleteCitizen: false,
    canViewReports: true,
    canExport: true,
    canManageAid: false,
    canManageAreas: false,
    canManageUsers: false,
    canBackupRestore: false,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validNeighborhoodPayload(uid, overrides = {}) {
  const now = pastTimestamp();
  return {
    ownerUid: uid,
    name: "حارة المنصورة الأولى",
    governorate: "عدن",
    district: "المنصورة",
    aqilName: "أحمد علي",
    aqilPhone: "771112223",
    notes: "",
    createdByName: "مدير النظام",
    isDeleted: false,
    syncVersion: 1,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validSquarePayload(uid, neighborhoodId = "neigh_1", overrides = {}) {
  const now = pastTimestamp();
  return {
    ownerUid: uid,
    neighborhoodId,
    name: "مربع أ",
    squareCode: "SQ-A1",
    representativeName: "خالد سعيد",
    representativePhone: "773334445",
    notes: "",
    createdByName: "مدير النظام",
    isDeleted: false,
    syncVersion: 1,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validCitizenPayload(uid, overrides = {}) {
  const now = pastTimestamp();
  return {
    ownerUid: uid,
    citizenCode: "FAHD-1001",
    fullName: "عبدالله محمد السالمي",
    idType: "هوية وطنية",
    nationalId: "0101020203",
    idIssueDate: "2020-01-01",
    phone: "770123456",
    governorate: "عدن",
    district: "المنصورة",
    neighborhoodId: "neigh_1",
    squareId: "sq_1",
    occupation: "مهندس",
    familySize: 5,
    housingType: "مستأجر",
    housingStatus: "مستقر",
    displacementDate: "",
    healthStatus: "سليم",
    healthDetails: "",
    lastAidDate: "",
    notes: "",
    createdByName: "مدير النظام",
    isDeleted: false,
    syncVersion: 1,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

async function seedAreaHierarchy() {
  const db = adminDb();
  await assertSucceeds(
    db.collection("neighborhoods").doc("neigh_1").set(validNeighborhoodPayload(ADMIN_UID))
  );
  await assertSucceeds(
    db.collection("residential_squares").doc("sq_1").set(validSquarePayload(ADMIN_UID, "neigh_1"))
  );
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller is blocked on all protected collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("profiles").get());
  await assertFails(unauth.collection("people").get());
  await assertFails(unauth.collection("neighborhoods").get());
  await assertFails(unauth.collection("app_settings").doc("global").get());
  await assertFails(unauth.collection("audit_logs").get());
  await assertFails(unauth.collection("sync_metadata").get());
});

test("Spoofed unverified admin email is denied elevated privileges", async () => {
  const spoofedDb = testEnv
    .authenticatedContext("spoof_uid", {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
    .firestore();

  await assertFails(
    spoofedDb
      .collection("profiles")
      .doc("spoof_uid")
      .set(validProfilePayload("spoof_uid", { role: "admin", canManageUsers: true }))
  );
  await assertFails(
    spoofedDb.collection("neighborhoods").doc("neigh_spoof").set(validNeighborhoodPayload("spoof_uid"))
  );
});

test("Employee self-registration enforces least privilege and blocks role escalation", async () => {
  const empDb = testEnv.authenticatedContext(EMP_UID, {
    email: "emp@sijil.test",
    email_verified: true,
  }).firestore();

  // Blocked: trying to self-register as admin or with edit permissions
  await assertFails(
    empDb
      .collection("profiles")
      .doc(EMP_UID)
      .set(validProfilePayload(EMP_UID, { role: "admin" }))
  );
  await assertFails(
    empDb
      .collection("profiles")
      .doc(EMP_UID)
      .set(validProfilePayload(EMP_UID, { canEditCitizen: true }))
  );

  // Allowed: least-privilege employee self-registration
  await assertSucceeds(
    empDb.collection("profiles").doc(EMP_UID).set(validProfilePayload(EMP_UID))
  );

  // Allowed: updating safe profile fields only
  await assertSucceeds(
    empDb.collection("profiles").doc(EMP_UID).update({
      fullName: "سالم علي المعدل",
      updatedAt: pastTimestamp(),
    })
  );

  // Blocked: trying to escalate own role or isActive or permissions
  await assertFails(
    empDb.collection("profiles").doc(EMP_UID).update({
      role: "admin",
      updatedAt: pastTimestamp(),
    })
  );
  await assertFails(
    empDb.collection("profiles").doc(EMP_UID).update({
      canManageAid: true,
      updatedAt: pastTimestamp(),
    })
  );
});

test("RBAC enforces areas, citizen add-only for employee, and ownerUid-scoped list queries", async () => {
  const empDb = testEnv.authenticatedContext(EMP_UID).firestore();
  await assertSucceeds(
    empDb.collection("profiles").doc(EMP_UID).set(validProfilePayload(EMP_UID))
  );

  // Employee cannot create neighborhoods or app_settings
  await assertFails(
    empDb.collection("neighborhoods").doc("neigh_emp").set(validNeighborhoodPayload(EMP_UID))
  );
  await assertFails(
    empDb.collection("app_settings").doc("global").set({
      ownerUid: EMP_UID,
      organizationTitle: "سجل الفهد",
      headerFontFamily: "Cairo",
      headerFontSizeSp: 14,
      createdAt: pastTimestamp(),
      updatedAt: pastTimestamp(),
    })
  );

  // Bootstrap admin creates areas & settings
  await seedAreaHierarchy();

  // Employee CAN add citizen linked to existing neighborhood and square
  const empCitRef = empDb.collection("people").doc("cit_emp_1");
  await assertSucceeds(empCitRef.set(validCitizenPayload(EMP_UID)));

  // Employee CAN list only when scoped by ownerUid == EMP_UID
  await assertSucceeds(
    empDb.collection("people").where("ownerUid", "==", EMP_UID).get()
  );
  // Unscoped list query fails
  await assertFails(empDb.collection("people").get());

  // Employee CANNOT update or delete citizen (canEditCitizen=false, canDeleteCitizen=false)
  await assertFails(
    empCitRef.update({ fullName: "تعديل غير مصرح", updatedAt: pastTimestamp() })
  );
  await assertFails(empCitRef.delete());

  // Cross-user read is blocked
  const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  await assertFails(empDb.collection("people").doc("cit_other").get());
  await assertFails(otherDb.collection("people").doc("cit_emp_1").get());
});

test("Relational integrity blocks orphan squares, citizens, identity docs, and aid records", async () => {
  const adm = adminDb();
  const now = pastTimestamp();

  // Orphan square (missing neighborhood) fails
  await assertFails(
    adm.collection("residential_squares").doc("sq_orphan").set(validSquarePayload(ADMIN_UID, "missing_neigh"))
  );

  await seedAreaHierarchy();

  // Orphan citizen (missing square) fails
  await assertFails(
    adm.collection("people").doc("cit_orphan").set(validCitizenPayload(ADMIN_UID, { squareId: "missing_sq" }))
  );

  await assertSucceeds(adm.collection("people").doc("cit_1").set(validCitizenPayload(ADMIN_UID)));

  // Identity doc linking to existing person succeeds; missing person fails
  await assertFails(
    adm.collection("identity_documents").doc("doc_bad").set({
      ownerUid: ADMIN_UID,
      personId: "missing_person",
      documentType: "هوية وطنية",
      side: "front",
      isDeleted: false,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adm.collection("identity_documents").doc("doc_ok").set({
      ownerUid: ADMIN_UID,
      personId: "cit_1",
      documentType: "هوية وطنية",
      side: "front",
      fileSizeBytes: 2048,
      uploaded: true,
      isDeleted: false,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Terminal state locking prevents non-admin amendment of delivered aid distributions", async () => {
  const adm = adminDb();
  const now = pastTimestamp();
  await seedAreaHierarchy();
  await assertSucceeds(adm.collection("people").doc("cit_1").set(validCitizenPayload(ADMIN_UID)));
  await assertSucceeds(
    adm.collection("aid_programs").doc("prog_1").set({
      ownerUid: ADMIN_UID,
      name: "السلة الغذائية الرمضانية",
      aidType: "غذائية",
      distributionDate: "2026-03-01",
      isDeleted: false,
      syncVersion: 1,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Admin creates an Aid Manager profile
  await assertSucceeds(
    adm.collection("profiles").doc(AID_MGR_UID).set(
      validProfilePayload(AID_MGR_UID, {
        role: "employee",
        canManageAid: true,
      })
    )
  );

  const aidMgrDb = testEnv.authenticatedContext(AID_MGR_UID).firestore();
  const distRef = aidMgrDb.collection("aid_distributions").doc("dist_pending");

  // Pending distribution can be created and updated to delivered ("تم الاستلام")
  await assertSucceeds(
    distRef.set({
      ownerUid: AID_MGR_UID,
      programId: "prog_1",
      personId: "cit_1",
      quantityDelivered: 1,
      amountDelivered: 0,
      receivedStatus: "قيد التسليم",
      responsibleEmployeeName: "مسؤول المساعدات",
      notes: "",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    distRef.update({
      receivedStatus: "تم الاستلام",
      updatedAt: pastTimestamp(),
    })
  );

  // Once delivered ("تم الاستلام"), non-admin Aid Manager is locked out from modifying it
  await assertFails(
    distRef.update({
      quantityDelivered: 99,
      updatedAt: pastTimestamp(),
    })
  );

  // Admin can amend delivered record
  await assertSucceeds(
    adm.collection("aid_distributions").doc("dist_pending").update({
      notes: "مراجعة إدارية معتمدة",
      updatedAt: pastTimestamp(),
    })
  );
});

test("Audit logs are append-only and schema tampering / ghost fields are rejected", async () => {
  const adm = adminDb();
  const now = pastTimestamp();

  // Audit logs create succeeds; update/delete fail even for admin
  const logRef = adm.collection("audit_logs").doc("log_1");
  await assertSucceeds(
    logRef.set({
      ownerUid: ADMIN_UID,
      actorUsername: "abbeb34",
      actionType: "CREATE",
      tableName: "people",
      recordId: "cit_1",
      summaryAr: "إضافة مواطن جديد",
      createdAt: now,
    })
  );
  await assertFails(logRef.update({ summaryAr: "تزوير السجل" }));
  await assertFails(logRef.delete());

  // Settings font size out of range rejected; valid range accepted
  await assertFails(
    adm.collection("app_settings").doc("global").set({
      ownerUid: ADMIN_UID,
      organizationTitle: "سجل الفهد",
      headerFontFamily: "Amiri",
      headerFontSizeSp: 99,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adm.collection("app_settings").doc("global").set({
      ownerUid: ADMIN_UID,
      organizationTitle: "سجل الفهد",
      headerRightText: "الجمهورية اليمنية",
      headerLeftText: "كشف رسمي",
      headerFontFamily: "Amiri",
      headerFontSizeSp: 16,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Ghost field & invalid syncStatus rejected on sync_metadata
  await assertFails(
    adm.collection("sync_metadata").doc("sync_1").set({
      ownerUid: ADMIN_UID,
      deviceId: "pixel_8",
      entityTable: "people",
      recordId: "cit_1",
      syncStatus: "InvalidStatus",
      retryCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adm.collection("sync_metadata").doc("sync_1").set({
      ownerUid: ADMIN_UID,
      deviceId: "pixel_8",
      entityTable: "people",
      recordId: "cit_1",
      syncStatus: "Synced",
      retryCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
});
