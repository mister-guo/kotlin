fun arrayAccessRHS(a: Int?, b: Array<Int>) {
    b[0] = a!!
    <!DEBUG_INFO_AUTOCAST!>a<!> : Int
}

fun arrayAccessLHS(a: Int?, b: Array<Int>) {
    b[a!!] = <!DEBUG_INFO_AUTOCAST!>a<!>
    <!DEBUG_INFO_AUTOCAST!>a<!> : Int
}

