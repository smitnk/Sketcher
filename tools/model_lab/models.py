"""Model definitions copied from controlnet_aux (Apache-2.0):
https://github.com/patrickvonplaten/controlnet_aux  (lineart/__init__.py, lineart_anime/__init__.py)
Weights: https://huggingface.co/lllyasviel/Annotators (see LICENSES.md).

Pre/post-processing (from controlnet_aux):
 realistic/coarse: Generator(3,1,3); input RGB /255 in [0,1]; output sigmoid "ink" map
                   (1 = line); displayed line image = 255 - out*255.
 anime: UnetGenerator(3,1,8,64, InstanceNorm affine=False); input RGB/127.5-1 in [-1,1];
        H and W must be multiples of 256 (8 stride-2 downs); out in [-1,1] (tanh);
        v = out*127.5+127.5; displayed = 255 - v, so RAW high = ink (same as realistic).
"""
import functools
import torch
import torch.nn as nn

norm_layer = nn.InstanceNorm2d


class ResidualBlock(nn.Module):
    def __init__(self, in_features):
        super().__init__()
        self.conv_block = nn.Sequential(
            nn.ReflectionPad2d(1), nn.Conv2d(in_features, in_features, 3),
            norm_layer(in_features), nn.ReLU(inplace=True),
            nn.ReflectionPad2d(1), nn.Conv2d(in_features, in_features, 3),
            norm_layer(in_features))

    def forward(self, x):
        return x + self.conv_block(x)


class Generator(nn.Module):
    def __init__(self, input_nc, output_nc, n_residual_blocks=9, sigmoid=True):
        super().__init__()
        self.model0 = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(input_nc, 64, 7),
                                    norm_layer(64), nn.ReLU(inplace=True))
        model1 = []
        in_f, out_f = 64, 128
        for _ in range(2):
            model1 += [nn.Conv2d(in_f, out_f, 3, stride=2, padding=1), norm_layer(out_f), nn.ReLU(inplace=True)]
            in_f, out_f = out_f, out_f * 2
        self.model1 = nn.Sequential(*model1)
        self.model2 = nn.Sequential(*[ResidualBlock(in_f) for _ in range(n_residual_blocks)])
        model3 = []
        out_f = in_f // 2
        for _ in range(2):
            model3 += [nn.ConvTranspose2d(in_f, out_f, 3, stride=2, padding=1, output_padding=1),
                       norm_layer(out_f), nn.ReLU(inplace=True)]
            in_f, out_f = out_f, out_f // 2
        self.model3 = nn.Sequential(*model3)
        model4 = [nn.ReflectionPad2d(3), nn.Conv2d(64, output_nc, 7)]
        if sigmoid:
            model4 += [nn.Sigmoid()]
        self.model4 = nn.Sequential(*model4)

    def forward(self, x):
        return self.model4(self.model3(self.model2(self.model1(self.model0(x)))))


class UnetGenerator(nn.Module):
    def __init__(self, input_nc, output_nc, num_downs, ngf=64, norm_layer=nn.BatchNorm2d, use_dropout=False):
        super().__init__()
        b = UnetSkipConnectionBlock(ngf * 8, ngf * 8, input_nc=None, submodule=None, norm_layer=norm_layer, innermost=True)
        for _ in range(num_downs - 5):
            b = UnetSkipConnectionBlock(ngf * 8, ngf * 8, input_nc=None, submodule=b, norm_layer=norm_layer, use_dropout=use_dropout)
        b = UnetSkipConnectionBlock(ngf * 4, ngf * 8, input_nc=None, submodule=b, norm_layer=norm_layer)
        b = UnetSkipConnectionBlock(ngf * 2, ngf * 4, input_nc=None, submodule=b, norm_layer=norm_layer)
        b = UnetSkipConnectionBlock(ngf, ngf * 2, input_nc=None, submodule=b, norm_layer=norm_layer)
        self.model = UnetSkipConnectionBlock(output_nc, ngf, input_nc=input_nc, submodule=b, outermost=True, norm_layer=norm_layer)

    def forward(self, x):
        return self.model(x)


class UnetSkipConnectionBlock(nn.Module):
    def __init__(self, outer_nc, inner_nc, input_nc=None, submodule=None, outermost=False,
                 innermost=False, norm_layer=nn.BatchNorm2d, use_dropout=False):
        super().__init__()
        self.outermost = outermost
        if isinstance(norm_layer, functools.partial):
            use_bias = norm_layer.func == nn.InstanceNorm2d
        else:
            use_bias = norm_layer == nn.InstanceNorm2d
        if input_nc is None:
            input_nc = outer_nc
        downconv = nn.Conv2d(input_nc, inner_nc, kernel_size=4, stride=2, padding=1, bias=use_bias)
        downrelu = nn.LeakyReLU(0.2, True)
        downnorm = norm_layer(inner_nc)
        uprelu = nn.ReLU(True)
        upnorm = norm_layer(outer_nc)
        if outermost:
            upconv = nn.ConvTranspose2d(inner_nc * 2, outer_nc, kernel_size=4, stride=2, padding=1)
            model = [downconv] + [submodule] + [uprelu, upconv, nn.Tanh()]
        elif innermost:
            upconv = nn.ConvTranspose2d(inner_nc, outer_nc, kernel_size=4, stride=2, padding=1, bias=use_bias)
            model = [downrelu, downconv] + [uprelu, upconv, upnorm]
        else:
            upconv = nn.ConvTranspose2d(inner_nc * 2, outer_nc, kernel_size=4, stride=2, padding=1, bias=use_bias)
            model = [downrelu, downconv, downnorm] + [submodule] + [uprelu, upconv, upnorm]
            if use_dropout:
                model += [nn.Dropout(0.5)]
        self.model = nn.Sequential(*model)

    def forward(self, x):
        if self.outermost:
            return self.model(x)
        return torch.cat([x, self.model(x)], 1)


def build_realistic():
    return Generator(3, 1, 3)


def build_anime():
    nl = functools.partial(nn.InstanceNorm2d, affine=False, track_running_stats=False)
    return UnetGenerator(3, 1, 8, 64, norm_layer=nl, use_dropout=False)
