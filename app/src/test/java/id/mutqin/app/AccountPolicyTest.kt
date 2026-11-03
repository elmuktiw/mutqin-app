package id.mutqin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountPolicyTest {
    @Test
    fun newAccountWithDeviceDataAsksStartFreshOrKeep() {
        assertEquals(
            AccountPrompt.NEW_ACCOUNT_WITH_DEVICE_DATA,
            AccountPolicy.prompt(firstSync = true, accountHasData = false, localReviewedPages = 30)
        )
    }

    @Test
    fun emptyDeviceJustTakesAccountData() {
        assertEquals(AccountPrompt.NONE, AccountPolicy.prompt(firstSync = true, accountHasData = true, localReviewedPages = 0))
        assertEquals(AccountPrompt.NONE, AccountPolicy.prompt(firstSync = true, accountHasData = false, localReviewedPages = 0))
    }

    @Test
    fun bothHaveDataAsksWhichToUse() {
        assertEquals(AccountPrompt.BOTH_HAVE_DATA, AccountPolicy.prompt(firstSync = true, accountHasData = true, localReviewedPages = 5))
    }

    @Test
    fun returningToSameAccountMergesSilently() {
        assertEquals(AccountPrompt.NONE, AccountPolicy.prompt(firstSync = false, accountHasData = true, localReviewedPages = 40))
    }
}
