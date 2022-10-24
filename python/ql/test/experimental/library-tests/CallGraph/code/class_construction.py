class X(object):
    def __init__(self, arg):
        print("X.__init__", arg)
        self.arg = arg

    def foo(self):
        print("X.foo", self.arg)

    def meth(self):
        print("X.meth")
        return type(self)(42.1) # $ MISSING: tt=X.__init__ tt=Y.__init__

    @classmethod
    def cm(cls):
        print("X.cm")
        cls(42.2) # $ MISSING: tt=X.__init__ tt=Y.__init__

x = X(42.0) # $ tt=X.__init__
x_421 = x.meth() # $ pt,tt=X.meth
X.cm() # $ pt,tt=X.cm
x.foo() # $ pt,tt=X.foo
print()
x_421.foo() # $ pt=X.foo MISSING: tt=X.foo
print()


class Y(X):
    def __init__(self, arg):
        print("Y.__init__", arg)
        super().__init__(-arg) # $ pt,tt=X.__init__

y = Y(43) # $ tt=Y.__init__
y.meth() # $ pt,tt=X.meth
y.cm() # $ pt,tt=X.cm
print()

# ---

class WithNew(object):
    def __new__(cls, arg):
        print("WithNew.__new__", arg)
        inst = super().__new__(cls)
        assert isinstance(inst, cls)
        inst.some_method() # $ MISSING: pt,tt=WithNew.some_method
        return inst

    def __init__(self, arg):
        print("WithNew.__init__", arg)

    def some_method(self):
        print("WithNew.__init__")

WithNew(44) # $ tt=WithNew.__new__ tt=WithNew.__init__
print()


class ExtraCallToInit(object):
    def __new__(cls, arg):
        print("ExtraCallToInit.__new__", arg)
        inst = super().__new__(cls)
        assert isinstance(inst, cls)
        # you're not supposed to do this, since it will cause the __init__ method will be run twice.
        inst.__init__(1001) # $ MISSING: pt,tt=ExtraCallToInit.__init__
        return inst

    def __init__(self, arg):
        print("ExtraCallToInit.__init__", arg, self)

ExtraCallToInit(1000) # $ tt=ExtraCallToInit.__new__ tt=ExtraCallToInit.__init__
print()


class InitNotCalled(object):
    """as described in https://docs.python.org/3/reference/datamodel.html#object.__new__
    __init__ will only be called when the returned object from __new__ is an instance of
    the `cls` parameter...
    """
    def __new__(cls, arg):
        print("InitNotCalled.__new__", arg)
        return False

    def __init__(self, arg):
        print("InitNotCalled.__init__", arg)

InitNotCalled(2000) # $ tt=InitNotCalled.__new__ SPURIOUS: tt=InitNotCalled.__init__
print()
