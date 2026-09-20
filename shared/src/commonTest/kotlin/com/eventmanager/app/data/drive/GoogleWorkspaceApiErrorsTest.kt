package com.eventmanager.app.data.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleWorkspaceApiErrorsTest {

    @Test
    fun serviceDisabledJsonBecomesATypedDriveError() {
        val raw = Exception(
            """403 Forbidden GET https://www.googleapis.com/drive/v3/files
            {"error":{"errors":[{"reason":"accessNotConfigured"}],"code":403,
            "message":"Google Drive API has not been used in project 568325038184 before or it is disabled."}}""",
        )

        val mapped = GoogleWorkspaceApiErrors.map(raw, WorkspaceApi.DRIVE)

        assertEquals(WorkspaceApiErrorKind.SERVICE_DISABLED, mapped.kind)
        assertEquals(WorkspaceApi.DRIVE, mapped.api)
        assertTrue(mapped.hidesRecentFilesList)
        assertTrue(mapped.message.length < 120, mapped.message)
    }
}
